"""The HTTP API with a fake model and a fake search index: no GPU, no download."""
import os
from datetime import datetime, timedelta

os.environ["VERBATIM_API_TOKEN"] = "test-token"

import pytest
from fastapi.testclient import TestClient

from server import config, main, rag, summarize

AUTH = {"Authorization": "Bearer test-token"}
RECORDING = {"filename": "a.m4a", "timestamp": 1_000, "durationMs": 5, "transcript": "Bonjour"}


class FakeStore:
    def add_transcript(self, *args):
        pass

    def delete_recording(self, *args):
        pass

    def search(self, *args, **kwargs):
        return []


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "recordings.db")
    monkeypatch.setattr(main, "get_embedding_store", lambda: FakeStore())
    monkeypatch.setattr(rag, "get_embedding_store", lambda: FakeStore())
    return TestClient(main.app)


def test_every_endpoint_requires_the_token(client):
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.post("/transcript", json=RECORDING).status_code == 401
    assert client.post("/query", json={"question": "x"}).status_code == 401
    assert client.delete("/recordings/x").status_code == 401
    assert client.get("/health", headers=AUTH).status_code == 200


def test_the_server_refuses_to_start_without_a_token(monkeypatch):
    for token in ("", "change-me"):
        monkeypatch.setattr(config, "API_TOKEN", token)
        with pytest.raises(RuntimeError):
            config.check_token()


def test_model_down_keeps_the_transcript_and_lets_the_phone_retry(client, monkeypatch):
    def down(*args, **kwargs):
        raise ConnectionError("model is off")

    monkeypatch.setattr(summarize, "chat", down)
    assert client.post("/transcript", json=RECORDING, headers=AUTH).status_code == 503
    conn = main._connect()
    assert conn.execute("SELECT transcript FROM recordings").fetchall() == [("Bonjour",)]
    conn.close()

    monkeypatch.setattr(summarize, "chat", lambda *a, **k: '{"summary": "Salut", "action_items": []}')
    body = client.post("/transcript", json=RECORDING, headers=AUTH).json()
    assert body["summary"] == "Salut"


def test_long_transcripts_are_cut_to_fit_the_model(client, monkeypatch):
    sent = {}

    def fake_chat(messages, **kwargs):
        sent["text"] = messages[1]["content"]
        return '{"summary": "ok", "action_items": []}'

    monkeypatch.setattr(summarize, "chat", fake_chat)
    monkeypatch.setattr(config, "CONTEXT_CHARS", 100)
    summarize.summarize_text("x" * 1000)
    assert len(sent["text"]) < 200


def test_a_dated_question_uses_only_that_period(client, monkeypatch):
    monkeypatch.setattr(summarize, "chat", lambda *a, **k: '{"summary": "s", "action_items": []}')
    now = datetime.now()
    for name, when in (("old.m4a", now - timedelta(days=30)), ("new.m4a", now - timedelta(days=1))):
        recording = {**RECORDING, "filename": name, "timestamp": int(when.timestamp() * 1000), "transcript": name}
        client.post("/transcript", json=recording, headers=AUTH)

    seen = {}

    def fake_chat(messages, **kwargs):
        seen["prompt"] = messages[0]["content"]
        return "answer"

    monkeypatch.setattr(rag, "chat", fake_chat)
    body = client.post("/query", json={"question": "What did I do yesterday?"}, headers=AUTH).json()
    assert body["answer"] == "answer"
    assert len(body["sources"]) == 1
    assert "new.m4a" in seen["prompt"] and "old.m4a" not in seen["prompt"]


def test_query_with_the_model_down_is_a_clear_503(client, monkeypatch):
    # The index is fake: give it one chunk, so the question reaches the model.
    monkeypatch.setattr(rag, "get_embedding_store", lambda: type("S", (FakeStore,), {
        "search": lambda self, *a, **k: [("text", {"recording_id": "r", "timestamp": 1000})]
    })())

    def down(*args, **kwargs):
        raise ConnectionError("model is off")

    monkeypatch.setattr(rag, "chat", down)
    assert client.post("/query", json={"question": "x"}, headers=AUTH).status_code == 503
