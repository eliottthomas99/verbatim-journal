"""Heuristics for the fictional-data smoke test, NOT an answer-quality judge."""
import re
import unicodedata


def check_answer(answer, sources, words, expected, kind):
    normalized = "".join(c for c in unicodedata.normalize("NFKD", answer.lower())
                         if not unicodedata.combining(c))
    if kind in ("absent", "absent-date"):
        # Check for an actual refusal instead of passing an empty keyword list.
        answer_check = bool(re.search(
            r"nothing|no (?:recordings?|information|mention)|not (?:find|contain)|don't|do not|"
            r"aucun|rien|pas (?:de|d'|trouve)|ne .{0,60} pas|n'ai .{0,30} pas", normalized
        ))
        # Undated search deliberately returns nearest matches, even if irrelevant.
        retrieval_check = not sources if kind == "absent-date" else None
    else:
        answer_check = bool(words) and any(w.lower() in answer.lower() for w in words)
        retrieval_check = (set(sources) == set(expected) if kind == "date"
                           else bool(expected) and set(expected).issubset(sources))
    return {"answer_smoke_check": answer_check, "retrieval_check": retrieval_check}
