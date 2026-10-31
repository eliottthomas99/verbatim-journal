"""Summary + action items of a transcript, via the local LLM."""
from server import config
from server.llm import chat, parse_json_object

SYSTEM_PROMPT = (
    "You summarize entries of a personal journal: transcripts of voice recordings "
    "(notes to self or conversations, from automatic speech recognition, so they "
    "may contain errors) or notes the user typed. Summarize the content directly "
    "(\"Buy light bulbs before Saturday\"), without phrases like \"the recording mentions\".\n"
    "Reply ONLY with a JSON object with these keys:\n"
    '- "summary": a concise paragraph summarizing the content\n'
    '- "action_items": an array of strings: tasks, decisions, commitments '
    "(who does what, when, if said). Empty array if there are none.\n"
    "IMPORTANT: never translate. Write the summary and the action items in the language "
    "of the transcript: if it is in French, both MUST be in French, keeping the "
    "speaker's own words where possible."
)


def summarize_text(text: str) -> dict:
    """Return {"summary": str, "action_items": [str]}.

    Raises if the model cannot be reached: the caller keeps the transcript and
    tells the phone to try again later.
    """
    if not text.strip():
        return {"summary": "", "action_items": []}
    content = chat([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"Transcript:\n\n{text[:config.CONTEXT_CHARS]}"},
    ])
    try:
        parsed = parse_json_object(content)
        items = parsed.get("action_items") or []
        return {
            "summary": str(parsed.get("summary", "")).strip(),
            "action_items": [str(item) for item in items if str(item).strip()],
        }
    except (ValueError, AttributeError) as e:
        # The model didn't return clean JSON: keep its text as the summary.
        print(f"Summary was not JSON ({e}); keeping raw text", flush=True)
        return {"summary": content, "action_items": []}
