"""Every setting of the server, read from the environment or a `.env` file at
the repository root (see `.env.example`)."""
import os
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
load_dotenv(ROOT / ".env")

# Shared secret: the phone must send it with every request.
API_TOKEN = os.environ.get("VERBATIM_API_TOKEN", "").strip()

# Any OpenAI-compatible chat endpoint (llama.cpp, llama-swap, LM Studio, Ollama...).
LLM_URL = os.environ.get("VERBATIM_LLM_URL", "http://127.0.0.1:8080/v1/chat/completions")
LLM_MODEL = os.environ.get("VERBATIM_LLM_MODEL", "local-model")

# Transcripts, summaries and the search index. Never commit this directory.
DATA_DIR = Path(os.environ.get("VERBATIM_DATA_DIR", ROOT / "data")).expanduser()

# Most characters of text sent to the model at once. Lower it for a small
# context window: about 3 characters per token leaves room for the answer.
CONTEXT_CHARS = int(os.environ.get("VERBATIM_CONTEXT_CHARS", "24000"))


def check_token() -> None:
    """Refuse to start an open server: it holds someone's private journal."""
    if not API_TOKEN or API_TOKEN == "change-me":
        raise RuntimeError(
            "VERBATIM_API_TOKEN is not set. Copy .env.example to .env and set it to the "
            'output of: python -c "import secrets; print(secrets.token_urlsafe(32))"'
        )
