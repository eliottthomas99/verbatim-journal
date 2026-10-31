"""The successful CLI path, without downloading any embedding weights."""
import runpy
import sys
import types

import pytest


def test_successful_download_exits_zero(monkeypatch):
    chroma = types.ModuleType("chromadb")
    chroma.PersistentClient = lambda **kw: types.SimpleNamespace(get_or_create_collection=lambda **kw: object())
    settings = types.ModuleType("chromadb.config")
    settings.Settings = lambda **kw: object()
    functions = types.ModuleType("chromadb.utils.embedding_functions")
    functions.SentenceTransformerEmbeddingFunction = lambda **kw: object()
    for name, module in {"chromadb":chroma, "chromadb.config":settings,
                         "chromadb.utils":types.ModuleType("chromadb.utils"),
                         "chromadb.utils.embedding_functions":functions}.items():
        monkeypatch.setitem(sys.modules, name, module)
    monkeypatch.setattr(sys, "argv", ["server.embed", "download"])
    monkeypatch.delitem(sys.modules, "server.embed", raising=False)
    with pytest.raises(SystemExit) as result:
        runpy.run_module("server.embed", run_name="__main__")
    assert result.value.code == 0
