# Architecture

Verbatim has two parts: an Android app that owns the audio, and a small Python server that owns the AI work on text.

## On the phone (Kotlin, Jetpack Compose)

1. **Record.** A foreground service records AAC audio to a private `.m4a` file, started from the app or a Quick Settings tile.
2. **Transcribe.** The file is decoded and resampled to 16 kHz mono, then transcribed on the phone by Parakeet v3 (670 MB, downloaded explicitly in Settings). It works through long recordings in pieces of about 30 seconds, cut at the quietest moment, to limit memory use. Missing/failed models keep the audio and show an error; after setup, the user explicitly chooses **Transcribe again**. There is no Whistle/Needle runtime or cloud fallback.
3. **Store.** Text, timed segments and sync state go in a local Room (SQLite) database, which keeps working with no network.
4. **Sync.** Only the text is sent, when a recording finishes, when the app starts and when it comes to the front. Failed syncs stay pending and retry at those moments. A deletion the server hasn't acknowledged is remembered and retried too.
5. **Ask and listen.** Spoken questions are recognized on the phone. Bundled Piper voices read answers aloud; if they fail, the app reports an error, never falling back to a potentially cloud-backed system engine.

## On your computer (Python, FastAPI)

Every journal API endpoint requires `Authorization: Bearer <token>`, and the server refuses to start without a token. FastAPI's API documentation/schema contain no journal data and remain accessible without it.

| Endpoint | What it does |
|---|---|
| `POST /transcript` | Stores the text, summarizes it, indexes it, returns the summary. A retry updates the same recording. |
| `POST /query` | Answers a question from the journal, returns the answer and its source recordings. |
| `DELETE /recordings/{id}` | Removes a recording's text and its search entries. |
| `GET /health` | Connection and token check, for the app's Settings screen. |

If the model is down, `POST /transcript` still stores the text and attempts indexing, then returns `503`. If indexing also fails, that work stays pending in SQLite. The phone keeps the recording pending and sends it again later to get its summary.

Both phone and server compute the same UUID v5 from the original filename and timestamp. The phone can therefore delete a submission even if the upload response was lost or returned `503`. It persists the deletion before dropping the local row. The server keeps an opaque deleted-ID marker, checks it again after slow summarization, and rejects uploads of that ID with `410`; no deleted transcript is kept in the marker.

SQLite also holds pending index work for updates/deletions. Startup, queries and later mutations retry it. An index failure returns `503` rather than acknowledging a complete sync or answering from stale chunks. Run only one server process per data directory; a process-wide lock serializes index/database mutations, not model calls.

## Answering a question

1. **Find the period.** `server/dates.py` turns words like *hier*, *lundi dernier* or *in March last year* into dates using plain code, because small models get calendar maths wrong. Words about the future or tasks (*demain*, *before Friday*, *what must I do*) don't filter by date: those things were planned in earlier recordings.
2. **Gather context.** An empty recognized period returns no recordings, without broadening the search. Otherwise SQLite supplies the period's recordings, with at most half the remaining context budget used for their overviews and the rest reserved for relevant search excerpts within that period. Headers and separators count toward the budget; a very large period cannot include every entry. Without a recognized period, search uses the whole journal. Unsupported multi-date ranges are not reduced to their first day. Embeddings use a multilingual model.
3. **Answer.** The model gets the context, up to `VERBATIM_CONTEXT_CHARS`, and is told to use only that.

## Decisions worth explaining

- **Transcribing on the phone** means audio can stay there, and the server only ever handles text. That makes the server much lighter too.
- **Bring your own model.** The client uses an OpenAI-compatible endpoint; other runtimes are candidates, not verified integrations. A small laptop and a powerful desktop use the same server code.
- **The laptop isn't always on.** The phone is built to work alone and catch up later, so the server doesn't have to be a home server.
- **The token is simple on purpose.** One person, one server, inside a private network that already encrypts traffic.
