from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
import json
import secrets
import sqlite3
import threading
import uuid

from server import config
from server.embed import get_embedding_store


def require_token(authorization: str = Header(default="")):
    """Every endpoint needs `Authorization: Bearer <VERBATIM_API_TOKEN>`."""
    # compare_digest takes the same time wherever the strings differ, so the
    # token cannot be guessed one character at a time. It needs bytes for non-ASCII.
    expected = f"Bearer {config.API_TOKEN}".encode()
    if not config.API_TOKEN or not secrets.compare_digest(authorization.encode(), expected):
        raise HTTPException(401, "Missing or wrong token")


app = FastAPI(title="Verbatim server", dependencies=[Depends(require_token)])

# The phone transcribes on device and sends only the text: no audio is ever
# stored on the server.
config.DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = config.DATA_DIR / "recordings.db"

# Every column the server code reads or writes. _connect() adds the missing ones
# to an existing database, so the schema can grow without deleting recordings.db.
RECORDING_COLUMNS = {
    "filename": "TEXT",  # name of the .m4a on the phone (the audio itself stays there)
    "timestamp": "INTEGER",
    "duration_ms": "INTEGER",
    "status": "TEXT DEFAULT 'synced'",
    "transcript": "TEXT",
    "segments_json": "TEXT",
    "speakers_json": "TEXT",
    "summary": "TEXT",
    "action_items": "TEXT",
}


def _connect() -> sqlite3.Connection:
    """Open the SQLite database, creating/upgrading the recordings table if needed."""
    conn = sqlite3.connect(DB_PATH)
    conn.execute("CREATE TABLE IF NOT EXISTS recordings (recording_id TEXT PRIMARY KEY)")
    existing = {row[1] for row in conn.execute("PRAGMA table_info(recordings)")}
    for name, decl in RECORDING_COLUMNS.items():
        if name not in existing:
            conn.execute(f"ALTER TABLE recordings ADD COLUMN {name} {decl}")
    conn.commit()
    return conn


class TranscriptSegment(BaseModel):
    text: str
    startMs: int
    endMs: int


class TranscriptRequest(BaseModel):
    filename: str
    timestamp: int  # epoch millis, when the recording started
    durationMs: int
    transcript: str
    segments: list[TranscriptSegment] = []


class QueryRequest(BaseModel):
    question: str


@app.on_event("startup")
def _start():
    """Stop if no token is configured, then load the embedding model in the
    background, so the first question after a restart does not wait for it."""
    config.check_token()

    def load():
        try:
            get_embedding_store()
        except Exception as e:
            print(f"Search warm-up failed: {e!r}", flush=True)

    threading.Thread(target=load, daemon=True).start()


@app.get("/health")
def health():
    return {"status": "ok"}


# Plain `def` (not async): FastAPI runs it in a worker thread, so a summary
# that takes minutes does not block the other requests.
@app.post("/transcript")
def submit_transcript(body: TranscriptRequest):
    """Store the transcript sent by the phone (text only, never audio),
    summarize it, index it for search, and return the summary."""
    # Same recording -> same id: a retry from the phone updates the row
    # instead of creating a duplicate.
    recording_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"moe:{body.filename}:{body.timestamp}"))

    conn = _connect()
    previous = conn.execute(
        "SELECT transcript, summary, action_items FROM recordings WHERE recording_id = ?",
        (recording_id,),
    ).fetchone()
    conn.close()
    model_failed = False
    if previous and previous[0] == body.transcript and previous[1]:
        # Already summarized (the phone timed out while waiting): reuse it.
        summary_result = {"summary": previous[1], "action_items": json.loads(previous[2] or "[]")}
    else:
        try:
            from server.summarize import summarize_text
            summary_result = summarize_text(body.transcript)
        except Exception as e:
            print(f"Summarization unavailable: {e!r}", flush=True)
            summary_result = {"summary": "", "action_items": []}
            model_failed = True

    conn = _connect()
    conn.execute(
        """INSERT OR REPLACE INTO recordings
           (recording_id, filename, timestamp, duration_ms, transcript, segments_json,
            status, summary, action_items)
           VALUES (?, ?, ?, ?, ?, ?, 'done', ?, ?)""",
        (recording_id, body.filename, body.timestamp, body.durationMs, body.transcript,
         json.dumps([s.model_dump() for s in body.segments]),
         summary_result["summary"], json.dumps(summary_result["action_items"])),
    )
    conn.commit()
    conn.close()

    # Index for search. A failure here must not fail the submit: the
    # transcript is stored, and `python -m server.embed reindex` can redo it.
    try:
        get_embedding_store().add_transcript(
            recording_id, body.transcript, summary_result["summary"], body.timestamp
        )
    except Exception as e:
        print(f"Indexing of {recording_id} failed: {e!r}", flush=True)

    if model_failed:
        # Stored and searchable, but the phone must send it again for its summary.
        raise HTTPException(503, "The model is not available")

    return {
        "recording_id": recording_id,
        "status": "done",
        "summary": summary_result["summary"],
        "action_items": summary_result["action_items"],
    }


@app.delete("/recordings/{recording_id}")
def delete_recording(recording_id: str):
    """Forget a recording the user deleted on the phone: its row and its
    search chunks. Idempotent — deleting twice (a retry) is fine."""
    conn = _connect()
    deleted = conn.execute("DELETE FROM recordings WHERE recording_id = ?", (recording_id,)).rowcount
    conn.commit()
    conn.close()
    try:
        get_embedding_store().delete_recording(recording_id)
    except Exception as e:
        # The phone retries until this succeeds: a deleted note must not stay searchable.
        raise HTTPException(503, f"Search index not updated: {e!r}")
    return {"recording_id": recording_id, "deleted": bool(deleted)}


@app.post("/query")
def query_rag(body: QueryRequest):
    """Answer a question using RAG over the stored transcripts."""
    from server.rag import answer_question
    try:
        return answer_question(body.question, _recordings_between)
    except Exception as e:
        print(f"Question failed: {e!r}", flush=True)
        raise HTTPException(503, "The model or the search index is not available")


def _recordings_between(start_ms: int, end_ms: int) -> list:
    conn = _connect()
    rows = conn.execute(
        """SELECT recording_id, timestamp, transcript, summary FROM recordings
           WHERE timestamp BETWEEN ? AND ? AND transcript IS NOT NULL AND transcript != ''
           ORDER BY timestamp""",
        (start_ms, end_ms),
    ).fetchall()
    conn.close()
    return rows
