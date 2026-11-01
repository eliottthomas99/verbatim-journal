# Third-party notices

Verbatim is licensed under the GNU General Public License v3.0. No model weights are stored in this repository: `tools/prepare_whistle.py` and `tools/prepare_tts.py` fetch them when you build, and each user chooses and downloads their own language model.

## Before distributing an APK

Publishing this source and listing licences is not by itself a complete source offer for every linked native component. Verify the corresponding-source requirements of the combined GPL-3.0 APK, including the exact native Needle engine and the espeak-ng/sherpa-onnx build. The pinned Needle release provides a prebuilt static library; matching native engine source has not yet been located in the linked upstream repository (which contains Python wrappers). APK publication remains pending that check. This does not change the Apache-2.0 labels on the upstream artifacts or claim that a full legal audit has been completed.

## Included in the app when built

| Component | Used for | Licence |
|---|---|---|
| [Cactus Whistle](https://huggingface.co/Cactus-Compute/whistle) model and Needle engine | Speech recognition | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 | Parakeet and text-to-speech runtime | Apache-2.0 |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | Text-to-phoneme conversion | GPL-3.0 |
| Piper voice `fr_FR-siwis-medium` | French speech | SIWIS dataset, CC BY 4.0, University of Edinburgh |
| Piper voice `en_US-ljspeech-medium` | English speech | LJ Speech dataset, public domain |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, Room, Retrofit, OkHttp, Gson | App libraries | Apache-2.0 |

## Downloaded only if the user asks

| Component | Used for | Licence |
|---|---|---|
| [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), int8 conversion by k2-fsa | Accurate speech recognition | CC BY 4.0 |

## Used by the server

| Component | Used for | Licence |
|---|---|---|
| [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) | Search embeddings | Apache-2.0 |
| FastAPI, Uvicorn, Requests, python-dotenv, ChromaDB, sentence-transformers | Server libraries | Installed by pip under their own licences |
