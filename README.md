# Verbatim

A private voice journal for Android that you run yourself. Record a conversation or a note, get a transcript on your phone, a summary from your own computer, then ask questions like *"qu'est-ce que j'ai fait hier ?"* or *"who did I ask to write the report?"*.

**Your audio never leaves your phone.** Only the transcript text goes to the server, and the server is a computer you own, running an AI model you choose. There is no account, no hosted service and no cloud provider.

<p>
  <img src="docs/screenshots/journal.png" width="24%" alt="The journal, with summaries of recordings and notes">
  <img src="docs/screenshots/recording.png" width="24%" alt="A note with its summary and action items">
  <img src="docs/screenshots/ask.png" width="24%" alt="A question answered from the journal, with sources">
  <img src="docs/screenshots/first-run.png" width="24%" alt="The first-run screen">
</p>

*All content in these screenshots is invented.*

## How it works

```
 Your Android phone                         Your computer
┌───────────────────────────┐              ┌─────────────────────────────┐
│ record → .m4a stays here  │  transcript  │ server (FastAPI)            │
│ transcribe on the phone   │ ───text────► │  stores text (SQLite)       │
│ read answers aloud        │ ◄──summary── │  search index (ChromaDB)    │
│                           │ ◄──answers── │  summaries, answers ──► your│
└───────────────────────────┘              │  local model (any OpenAI-   │
        private network                    │  compatible server)         │
        (e.g. Tailscale)                   └─────────────────────────────┘
```

More detail: [docs/architecture.md](docs/architecture.md).

Your computer doesn't have to stay on. While it's off, the phone keeps recording and transcribing, and the text waits. It's sent when the app is next opened or brought to the front with the server reachable. Sync doesn't run in the background, and saved summaries stay readable, but new questions need the server.

## What you need

- An **arm64 Android phone**, Android 8 or newer. The accurate speech model uses about 1 GB of memory more while transcribing.
- A **computer for the server**: Python 3.12 and a local model server. A GPU with 4 GB of memory is enough for the tested model.
- A **private network** between the two, such as [Tailscale](https://tailscale.com) (free for personal use).
- To build the app: Android Studio, with the SDK and NDK it offers to install.

## 1. Start a model

Any server with an OpenAI-compatible `/v1/chat/completions` endpoint should work: llama.cpp, llama-swap, LM Studio, Ollama. The tested route is [llama.cpp](https://github.com/ggml-org/llama.cpp) with **Gemma 4 E4B, Q4_K_M** (a 5.3 GB GGUF file, from `lmstudio-community/gemma-4-E4B-it-GGUF` on Hugging Face). You download the model yourself, under its own licence:

```
llama-server -m gemma-4-E4B-it-Q4_K_M.gguf -ngl 99 -c 16384 --jinja --host 127.0.0.1 --port 8080 -a gemma-4-e4b
```

The image file (`mmproj`) isn't needed. Bigger models usually write better answers, if your hardware can run them. On a small GPU, close games and other heavy programs first: they share the same memory.

## 2. Start the server

```
git clone <this repository>
cd <repository>
python -m venv .venv
.venv\Scripts\activate            # Linux and macOS: source .venv/bin/activate
pip install -r server/requirements.txt
```

Copy `.env.example` to `.env`, then set two values:

```
VERBATIM_API_TOKEN=<output of: python -c "import secrets; print(secrets.token_urlsafe(32))">
VERBATIM_LLM_MODEL=gemma-4-e4b
```

Download the embedding model (about 460 MB), then start the server on your private network address:

```
python -m server.embed download
python -m uvicorn server.main:app --host <your Tailscale IP> --port 8000
```

`tailscale ip -4` prints that address. Your firewall may ask you to allow Python on this network; allow only private networks.

Your journal is stored in `data/`. Back up that folder and `.env`; both are ignored by git.

> **Never expose this server to the public internet.** The token is sent over plain HTTP, which is only safe inside an encrypted private network like Tailscale. Don't forward the port on your router.

To check a server and model with fictional recordings, use an **empty** data directory and run `python tools/e2e_test.py results.json http://<address>:8000`, with `VERBATIM_API_TOKEN` set in that terminal.

## 3. Build the app

Open the project once in Android Studio and let it sync, which installs what's missing and creates `local.properties`. To build from a terminal instead, set `ANDROID_HOME` to your Android SDK folder and `JAVA_HOME` to Android Studio's bundled JDK (its `jbr` folder).

The speech and voice models aren't in git. Fetch them once, with checksums verified, then build with a phone plugged in with USB debugging on:

```
python -I tools/prepare_whistle.py
python -I tools/prepare_tts.py
gradlew installDebug              # Linux and macOS: ./gradlew installDebug
```

The APK is about 90 MB.

## 4. Connect them

1. Install Tailscale on the phone, signed in to the same account.
2. Open Verbatim, tap **Connect your server**.
3. Enter `http://<your Tailscale IP>:8000` and the token, then tap **Save & test**. It should say **Connected**.
4. Optional: in Settings, download the accurate speech model, Parakeet (670 MB). You can also add the **Record** tile to Quick Settings.

## What has been tested

| | |
|---|---|
| Server and model | Windows 11, a 4 GB laptop GPU, Gemma 4 E4B Q4_K_M (3.5 GB of GPU memory, a few seconds per summary) |
| Quality check | `tools/e2e_test.py`, fictional recordings: French stayed French, English stayed English, and the right recording was found for every question |
| Phone to server | An arm64 Android phone over Tailscale: first-run setup, wrong and right token, offline note syncing on connect, summaries, questions in the app, deleting from both sides |
| Fresh install | This README's server steps on a clean copy with an empty model cache |
| Automated tests | `pytest server/tests` (server, with a fake model) and `gradlew testDebugUnitTest` (app) |

Only this route has been tested from start to finish. Other model servers and operating systems should work but haven't been verified on this version.

## Known limits

- **Android only**, arm64 only. No iPhone version is planned.
- **Summaries and answers depend on your model.** Small models can leave things out of a long answer, or occasionally answer in the wrong language.
- **A game or other GPU-heavy program slows answers a lot** on a small GPU: from about 10 seconds to well over a minute in testing.
- **Sources may include unrelated recordings** when a question has no date, because search always returns the closest matches.
- **The light speech model (Whistle) is mediocre on real French speech.** Parakeet is much better. Recordings up to 5 minutes have been measured with it; longer ones are untested. A sentence falling on one of its 30-second cuts can lose accuracy.
- **Editing a transcript drops its timed lines**, so tap-to-play no longer works on that recording.
- **Date questions** understand common French and English phrasings; anything else searches all dates. The server's clock and time zone are used.
- **Long transcripts** are cut to `VERBATIM_CONTEXT_CHARS` characters for the summary. They're still stored and searchable in full.
- **No export or import yet**, and transcripts are stored unencrypted on the phone's private storage and on your computer.
- **The privacy claim has a boundary.** Tests measured no outgoing traffic from the Whistle engine during transcription, but its closed native library hasn't had a full privacy audit. The app downloads Parakeet only if you ask.

## Recording law

Recording a private conversation without consent is illegal in many places, including France (Code pénal, art. 226-1). Getting consent is your responsibility.

## Licence

[GPL-3.0](LICENSE). Verbatim includes espeak-ng, which is GPL-3.0. Other components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
