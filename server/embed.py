"""Vector store of transcript chunks for RAG search.

The embedding model and ChromaDB are loaded lazily (get_embedding_store()),
so the server starts in a few seconds.

Download the embedding model ahead of time (otherwise the first start does it):
    python -m server.embed download
Re-index everything already in SQLite (e.g. after deleting the chroma directory):
    python -m server.embed reindex
"""
import os
import threading
from datetime import datetime
from pathlib import Path

from server import config

# No anonymous usage statistics sent by ChromaDB.
os.environ.setdefault("ANONYMIZED_TELEMETRY", "False")

MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
CHROMA_DIR = config.DATA_DIR / "chroma"
# The model reads at most 128 tokens per chunk: ~400 characters of French.
CHUNK_SIZE = 400
CHUNK_OVERLAP = 80


class EmbeddingStore:
    def __init__(self, persist_directory: Path = CHROMA_DIR):
        import chromadb
        from chromadb.config import Settings
        from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction

        self.client = chromadb.PersistentClient(
            path=str(persist_directory),
            settings=Settings(anonymized_telemetry=False),
        )
        self.collection = self.client.get_or_create_collection(
            name="transcripts",
            embedding_function=SentenceTransformerEmbeddingFunction(model_name=MODEL_NAME),
            configuration={"hnsw": {"space": "cosine"}},
        )

    def add_transcript(self, recording_id: str, text: str, summary: str, timestamp_ms: int):
        """(Re)index one recording: its summary + overlapping transcript chunks.

        Replaces any previous chunks of the same recording, so a retried
        submit does not create duplicates.
        """
        self.collection.delete(where={"recording_id": recording_id})
        date_str = datetime.fromtimestamp(timestamp_ms / 1000).strftime("%Y-%m-%d")
        chunks = ([f"Summary: {summary}"] if summary else []) + chunk_text(text)
        if not chunks:
            return
        self.collection.add(
            ids=[f"{recording_id}_chunk_{i}" for i in range(len(chunks))],
            documents=chunks,
            metadatas=[
                {
                    "recording_id": recording_id,
                    "date": date_str,
                    "timestamp": int(timestamp_ms),
                    "chunk_index": i,
                }
                for i in range(len(chunks))
            ],
        )

    def delete_recording(self, recording_id: str):
        """Remove every chunk of one recording (the user deleted it)."""
        self.collection.delete(where={"recording_id": recording_id})

    def search(self, query: str, limit: int = 8, start_ms: int = None, end_ms: int = None) -> list:
        """Return [(chunk, metadata)] closest in meaning to the query,
        optionally only from recordings made between start_ms and end_ms."""
        count = self.collection.count()
        if count == 0:
            return []
        where = None
        if start_ms is not None and end_ms is not None:
            where = {"$and": [{"timestamp": {"$gte": start_ms}}, {"timestamp": {"$lte": end_ms}}]}
        results = self.collection.query(
            query_texts=[query],
            n_results=min(limit, count),
            where=where,
            include=["documents", "metadatas"],
        )
        return list(zip(results["documents"][0], results["metadatas"][0]))


def chunk_text(text: str, chunk_size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list:
    """Split text into overlapping chunks, preferably at a sentence end."""
    text = text.strip()
    if not text:
        return []
    chunks, start = [], 0
    while True:
        end = start + chunk_size
        if end >= len(text):
            chunks.append(text[start:])
            return chunks
        window = text[start:end]
        cut = max(window.rfind(". "), window.rfind("? "), window.rfind("! "), window.rfind("\n"))
        if cut > chunk_size // 2:  # don't make chunks too small
            end = start + cut + 1
        chunks.append(text[start:end].strip())
        start = end - overlap


_store = None
_store_lock = threading.Lock()


def get_embedding_store() -> EmbeddingStore:
    """The shared store, created on first use (loading the model takes seconds)."""
    global _store
    with _store_lock:
        if _store is None:
            _store = EmbeddingStore()
        return _store


if __name__ == "__main__":
    import sys

    if sys.argv[1:] == ["download"]:
        get_embedding_store()
        print(f"{MODEL_NAME} is ready")
        sys.exit(0)
    if sys.argv[1:] != ["reindex"]:
        sys.exit("usage: python -m server.embed download|reindex")
    from server.main import _connect, _repair_index

    conn = _connect()
    conn.execute("UPDATE recordings SET index_pending = 1")
    conn.execute("UPDATE deleted_recordings SET index_pending = 1")
    conn.commit()
    conn.close()
    store = get_embedding_store()
    _repair_index(store)
    print(f"reindexed journal: {store.collection.count()} chunks")
