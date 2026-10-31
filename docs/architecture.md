# Architecture

Verbatim has two parts: an Android app that owns the audio, and a small Python server that owns the AI work on text.

## On the phone (Kotlin, Jetpack Compose)

1. **Record.** A foreground service records AAC audio to a private `.m4a` file, started from the app or a Quick Settings tile.
2. **Transcribe.** The file is decoded and resampled to 16 kHz mono, then transcribed on the phone by Whistle (17 MB, included) or Parakeet v3 (670 MB, optional). Parakeet works through long recordings in pieces of about 30 seconds, cut at the quietest moment, to limit memory use.
3. **Store.** Text, timed segments and sync state go in a local Room (SQLite) database, which keeps working with no network.
4. **Sync.** Only the text is sent, when a recording finishes, when the app starts and when it comes to the front. Failed syncs stay pending and retry at those moments. A deletion the server hasn't acknowledged is remembered and retried too.
5. **Ask and listen.** Spoken questions are recognized on the phone. Piper voices read answers aloud on the phone.

## On your computer (Python, FastAPI)

Every endpoint requires `Authorization: Bearer <token>`, and the server refuses to start without a token.

| Endpoint | What it does |
|---|---|
| `POST /transcript` | Stores the text, summarizes it, indexes it, returns the summary. A retry updates the same recording. |
| `POST /query` | Answers a question from the journal, returns the answer and its source recordings. |
| `DELETE /recordings/{id}` | Removes a recording's text and its search entries. |
| `GET /health` | Connection and token check, for the app's Settings screen. |

If the model is down, `POST /transcript` still stores and indexes the text, then returns `503`. The phone keeps the recording pending and sends it again later to get its summary.

## Answering a question

1. **Find the period.** `server/dates.py` turns words like *hier*, *lundi dernier* or *in March last year* into dates using plain code, because small models get calendar maths wrong. Words about the future or tasks (*demain*, *before Friday*, *what must I do*) don't filter by date: those things were planned in earlier recordings.
2. **Gather context.** If there's a period, every recording in it comes from SQLite, plus the closest search results within it. If there's none, the closest results from the whole journal are used. Embeddings use a multilingual model, because recordings can mix French and English.
3. **Answer.** The model gets the context, up to `VERBATIM_CONTEXT_CHARS`, and is told to use only that.

## Decisions worth explaining

- **Transcribing on the phone** means audio can stay there, and the server only ever handles text. That makes the server much lighter too.
- **Bring your own model.** Anything OpenAI-compatible works, so a small laptop and a powerful desktop use the same code.
- **The laptop isn't always on.** The phone is built to work alone and catch up later, so the server doesn't have to be a home server.
- **The token is simple on purpose.** One person, one server, inside a private network that already encrypts traffic.
