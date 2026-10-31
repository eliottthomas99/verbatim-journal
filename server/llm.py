"""Tiny client for a local OpenAI-compatible model server (chat/completions)."""
import json
import re

import requests

from server import config


def chat(messages: list, max_tokens: int = 1024, timeout: int = 600) -> str:
    """Send one chat request and return the answer text. Raises on any failure.

    Thinking is disabled: the answers are short and it makes them ~15x faster
    (measured: 2.3 s instead of ~35 s for a short summary).
    """
    payload = {
        "model": config.LLM_MODEL,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": 0.3,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    response = requests.post(config.LLM_URL, json=payload, timeout=timeout)
    response.raise_for_status()
    content = response.json()["choices"][0]["message"].get("content") or ""
    # Some templates still leak a <think> block into the content.
    return re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()


def parse_json_object(text: str):
    """Extract the first JSON object from a model answer (tolerates ```json fences)."""
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no JSON object in the model's answer")
    return json.loads(text[start:end + 1])
