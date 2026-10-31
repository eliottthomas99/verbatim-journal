"""Answer a question about the recordings (RAG = retrieve, then generate).

1. server.dates turns time words ("yesterday", "lundi dernier"...) into a date
   range — embeddings alone cannot know what "yesterday" means.
2. Context = every recording of that range (from SQLite) plus the transcript
   chunks closest in meaning (ChromaDB), or only the closest chunks when the
   question has no date.
3. The LLM answers from that context only.
"""
from datetime import date, datetime, time, timedelta

from server import config
from server.dates import parse_period
from server.embed import get_embedding_store
from server.llm import chat

ANSWER_PROMPT = (
    "You answer questions about the user's own voice recordings (a personal "
    "journal: notes to self and conversations). Today is {today}. Use ONLY the "
    "context below; it comes from automatic speech recognition and may contain "
    "errors. Mention when things were said (day, time) when useful. If asked "
    "to summarize a period, cover the available recordings in the context: they "
    "are from that period. Excerpts may be shortened: do not claim exhaustive "
    "coverage. If the context does not contain the answer, say so "
    "plainly. Answer in the "
    "language of the question, briefly, in plain text without Markdown (no "
    "asterisks, no headings): it is shown in a phone app and may be read "
    "aloud.\n\nContext:\n{context}"
)


def _today() -> str:
    return datetime.now().strftime("%A %Y-%m-%d %H:%M")


def extract_date_range(question: str):
    """Return (start_ms, end_ms) for the period the question refers to, or None."""
    period = parse_period(question, date.today())
    if not period:
        return None
    start, end = period
    start_ms = int(datetime.combine(start, time.min).timestamp() * 1000)
    end_ms = int(datetime.combine(end + timedelta(days=1), time.min).timestamp() * 1000) - 1
    return start_ms, end_ms


def _header(recording_id: str, timestamp_ms: int) -> str:
    when = datetime.fromtimestamp(timestamp_ms / 1000).strftime("%A %Y-%m-%d %H:%M")
    return f"[Recording {recording_id} — {when}]"


def answer_question(question: str, recordings_between) -> dict:
    """recordings_between(start_ms, end_ms) -> [(recording_id, timestamp, transcript, summary)]."""
    date_range = extract_date_range(question)
    parts, sources, used = [], [], 0

    def add(recording_id, text, limit=None):
        nonlocal used
        separator = "\n\n---\n\n" if parts else ""
        available = max(0, config.CONTEXT_CHARS - used)
        if limit is not None:
            available = min(available, limit)
        text = text[: max(0, available - len(separator))]
        if text:
            parts.append(text)
            used += len(separator) + len(text)
            if recording_id is not None and recording_id not in sources:
                sources.append(recording_id)

    rows = recordings_between(*date_range) if date_range else []
    if date_range and not rows:
        start = datetime.fromtimestamp(date_range[0] / 1000).strftime("%Y-%m-%d")
        end = datetime.fromtimestamp(date_range[1] / 1000).strftime("%Y-%m-%d")
        return {"answer": f"No recordings between {start} and {end}.", "sources": []}

    store = get_embedding_store()
    if date_range:
        first = datetime.fromtimestamp(date_range[0] / 1000).strftime("%Y-%m-%d")
        last = datetime.fromtimestamp(date_range[1] / 1000).strftime("%Y-%m-%d")
        add(None, f"(Recordings from {first} to {last}; excerpts may be shortened.)")
        # At most half the remaining space for overviews, including headers.
        # Keep the other half for relevant chunks, including long-recording tails.
        share = max(0, config.CONTEXT_CHARS - used) // 2 // len(rows)
        for recording_id, timestamp, transcript, summary in rows:
            body = f"Summary: {summary}\n" if summary else ""
            body += f"Transcript: {transcript}"
            overview = f"{_header(recording_id, timestamp)}\n{body}"
            if share > len(_header(recording_id, timestamp)) + 10:
                add(recording_id, overview, share)
        chunks = store.search(question, limit=6, start_ms=date_range[0], end_ms=date_range[1])
    else:
        chunks = store.search(question, limit=8)

    for doc, meta in chunks:
        add(meta["recording_id"], f"{_header(meta['recording_id'], meta['timestamp'])} (excerpt)\n{doc}")

    if not sources:
        return {"answer": "I found nothing about that in your recordings.", "sources": []}

    answer = chat([
        {"role": "system", "content": ANSWER_PROMPT.format(
            today=_today(), context="\n\n---\n\n".join(parts))},
        {"role": "user", "content": question},
    ])
    return {"answer": answer, "sources": sources}
