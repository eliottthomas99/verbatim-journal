"""Synthetic data only; no model downloads or real journal access."""
import json
import os
import sqlite3
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

os.environ["VERBATIM_API_TOKEN"] = "test-token"

import pytest
from fastapi.testclient import TestClient
from server import config, main, rag, summarize

AUTH = {"Authorization": "Bearer test-token"}
NOTE = {"filename": "fictional.m4a", "timestamp": 1000, "durationMs": 100, "transcript": "Invented original"}
ID = str(uuid.uuid5(uuid.NAMESPACE_URL, "moe:fictional.m4a:1000"))


class Store:
    def __init__(self):
        self.docs = {}
        self.fail = False

    def add_transcript(self, recording_id, text, summary, timestamp):
        if self.fail:
            raise RuntimeError("simulated index outage")
        self.docs[recording_id] = (text, {"recording_id": recording_id, "timestamp": timestamp})

    def delete_recording(self, recording_id):
        if self.fail:
            raise RuntimeError("simulated index outage")
        self.docs.pop(recording_id, None)

    def search(self, question, **kwargs):
        return list(self.docs.values())


@pytest.fixture
def setup(tmp_path, monkeypatch):
    store = Store()
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "recordings.db")
    monkeypatch.setattr(main, "get_embedding_store", lambda: store)
    monkeypatch.setattr(rag, "get_embedding_store", lambda: store)
    monkeypatch.setattr(summarize, "chat", lambda *a, **k: '{"summary":"Invented summary","action_items":[]}')
    monkeypatch.setattr(rag, "chat", lambda *a, **k: "Invented answer")
    return TestClient(main.app), store


def test_failed_first_sync_can_be_deleted_without_a_response_id(setup, monkeypatch):
    client, store = setup
    def down(*args, **kwargs):
        raise ConnectionError("simulated model outage")
    monkeypatch.setattr(summarize, "chat", down)
    assert client.post("/transcript", headers=AUTH, json=NOTE).status_code == 503
    assert ID in store.docs
    assert client.delete(f"/recordings/{ID}", headers=AUTH).status_code == 200
    assert ID not in store.docs
    assert client.post("/transcript", headers=AUTH, json=NOTE).status_code == 410
    with main._connect() as conn:
        assert conn.execute("SELECT count(*) FROM recordings").fetchone()[0] == 0


def test_deletion_wins_over_an_in_flight_upload(setup, monkeypatch):
    client, store = setup
    entered, resume = threading.Event(), threading.Event()
    def slow(*args, **kwargs):
        entered.set()
        assert resume.wait(5)
        return '{"summary":"Invented summary","action_items":[]}'
    monkeypatch.setattr(summarize, "chat", slow)
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(client.post, "/transcript", headers=AUTH, json=NOTE)
        try:
            assert entered.wait(5)
            assert client.delete(f"/recordings/{ID}", headers=AUTH).status_code == 200
        finally:
            resume.set()
        assert future.result().status_code == 410
    assert ID not in store.docs


def test_index_failure_is_durable_and_queries_repair_it(setup):
    client, store = setup
    assert client.post("/transcript", headers=AUTH, json=NOTE).status_code == 200
    store.fail = True
    edited = {**NOTE, "transcript": "Invented correction"}
    assert client.post("/transcript", headers=AUTH, json=edited).status_code == 503
    assert client.post("/query", headers=AUTH, json={"question":"What changed?"}).status_code == 503
    with main._connect() as conn:
        assert conn.execute("SELECT index_pending FROM recordings").fetchone()[0] == 1
    store.fail = False
    assert client.post("/query", headers=AUTH, json={"question":"What changed?"}).status_code == 200
    assert store.docs[ID][0] == "Invented correction"


def test_failed_index_deletion_survives_and_is_repaired(setup):
    client, store = setup
    client.post("/transcript", headers=AUTH, json=NOTE)
    store.fail = True
    assert client.delete(f"/recordings/{ID}", headers=AUTH).status_code == 503
    store.fail = False
    main._repair_index()
    assert ID not in store.docs
    assert client.post("/transcript", headers=AUTH, json=NOTE).status_code == 410


def test_empty_date_range_does_not_search_other_dates(setup, monkeypatch):
    client, store = setup
    client.post("/transcript", headers=AUTH, json=NOTE)
    monkeypatch.setattr(rag, "extract_date_range", lambda q: (2000, 3000))
    def never(*a, **k):
        pytest.fail("Empty periods must not search or ask the model")
    monkeypatch.setattr(store, "search", never)
    monkeypatch.setattr(rag, "chat", never)
    body = client.post("/query", headers=AUTH, json={"question":"What happened then?"}).json()
    assert body["sources"] == []
    assert "No recordings" in body["answer"]


def test_dated_long_recording_keeps_relevant_tail_and_context_bound(setup, monkeypatch):
    _, store = setup
    monkeypatch.setattr(config, "CONTEXT_CHARS", 500)
    monkeypatch.setattr(rag, "extract_date_range", lambda q: (0, 2000))
    store.docs[ID] = ("UNIQUE_TAIL_FACT", {"recording_id": ID, "timestamp": 1000})
    seen = {}
    def capture(messages):
        seen["context"] = messages[0]["content"].split("Context:\n", 1)[1]
        return "Invented answer"
    monkeypatch.setattr(rag, "chat", capture)
    rag.answer_question("tail?", lambda *a: [(ID, 1000, "x" * 1000, "")])
    assert "UNIQUE_TAIL_FACT" in seen["context"]
    assert len(seen["context"]) <= 500


@pytest.mark.parametrize("result", [
    {"summary":"ok", "action_items":"Buy milk"},
    {"summary":None, "action_items":[]},
    {"summary":" ", "action_items":[]},
    {"summary":"ok", "action_items":[{}]},
    {},
])
def test_bad_model_shapes_are_retryable_not_synced(setup, monkeypatch, result):
    client, _ = setup
    monkeypatch.setattr(summarize, "chat", lambda *a, **k: json.dumps(result))
    assert client.post("/transcript", headers=AUTH, json=NOTE).status_code == 503


def test_valid_model_strings_are_trimmed(monkeypatch):
    monkeypatch.setattr(summarize, "chat", lambda *a, **k: '{"summary":" ok ","action_items":[" Buy milk ",""]}')
    assert summarize.summarize_text("Invented grocery list") == {"summary":"ok", "action_items":["Buy milk"]}


def test_existing_database_rows_survive_index_work_migration(setup):
    _, store = setup
    conn = sqlite3.connect(main.DB_PATH)
    conn.execute("CREATE TABLE recordings (recording_id TEXT PRIMARY KEY, transcript TEXT, summary TEXT, timestamp INTEGER)")
    conn.execute("INSERT INTO recordings VALUES (?, ?, ?, ?)", (ID, "Invented existing note", "Existing summary", 1000))
    conn.commit()
    conn.close()
    main._repair_index()
    assert store.docs[ID][0] == "Invented existing note"
    with main._connect() as upgraded:
        assert upgraded.execute("SELECT transcript, summary, index_pending FROM recordings").fetchone() == (
            "Invented existing note", "Existing summary", 0)
