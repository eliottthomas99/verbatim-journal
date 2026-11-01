# Third-party notices

Verbatim is licensed under the GNU General Public License v3.0. No model weights are stored in this repository. `tools/prepare_tts.py` fetches the bundled voices; Parakeet is downloaded explicitly in the app, and each user chooses and downloads their own server language model. Whistle and the Needle native runtime are no longer included.

## Before distributing an APK

Removing Needle resolves the identified unavailable-native-source component, not all release obligations. Piper includes GPL-3.0-or-later espeak-ng. APK publication remains pending the exact prebuilt native build provenance and complete corresponding-source review described in [the native-source checklist](tools/NATIVE_SOURCES.md), along with final device/release checks. A list of licences or source URLs is not by itself a complete source offer. No complete legal or native privacy audit is claimed.

`tools/prepare_licenses.py` bundles the full GPL text, this notice, pinned native licence texts and ONNX Runtime's third-party notices. They are accessible from Settings → Open-source licences. `tools/native_licenses.json` records the exact upstream URLs and SHA-256 hashes; `tools/native_sources.json` records verified baseline source archives. These notices preserve upstream attribution and do not relicense upstream components.

## Included in the app when built

| Component | Used for | Licence |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 | Parakeet and text-to-speech runtime | Apache-2.0 |
| [espeak-ng fork](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1) | Text-to-phoneme conversion | GPL-3.0-or-later; additional Apache/BSD/Unicode notices for included portions |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/v1.28.2) | Neural inference, statically included in sherpa | MIT and upstream third-party notices |
| [piper-phonemize](https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4) | Piper phonemization | MIT |
| kaldi-native-fbank, kaldi-decoder, OpenFst, simple-sentencepiece | Speech features/decoding in sherpa | Apache-2.0; see bundled original notices |
| nlohmann/json | Native JSON support | MIT |
| hclust-cpp, kissfft | Native clustering/FFT support | BSD-style; see bundled original texts |
| Piper voice `fr_FR-siwis-medium` | French speech | SIWIS dataset, CC BY 4.0, University of Edinburgh |
| Piper voice `en_US-ljspeech-medium` | English speech | LJ Speech dataset, public domain |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, Room, Retrofit, OkHttp, Gson | App libraries | Apache-2.0 |

## Downloaded explicitly before transcription

| Component | Used for | Licence |
|---|---|---|
| [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [int8 ONNX conversion by k2-fsa](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78) | On-device speech recognition | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); converted/quantized weights, not the original checkpoint |

The French voice's SIWIS dataset is attributed to the University of Edinburgh
([dataset](https://datashare.is.ed.ac.uk/handle/10283/2353), CC BY 4.0); its original
model card notes fine-tuning from the English lessac voice. The English voice's
LJ Speech dataset is public domain ([dataset](https://keithito.com/LJ-Speech-Dataset/));
the original voice card credits its training source at https://brycebeattie.com/files/tts/.
Both original cards are preserved in the APK. Voices are upstream int8 conversions;
Verbatim does not train or modify their weights. Model and training provenance
has not been independently legally audited. The full CC BY 4.0 text is bundled.

## Used by the server

| Component | Used for | Licence |
|---|---|---|
| [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) | Search embeddings | Apache-2.0 |
| FastAPI, Uvicorn, Requests, python-dotenv, ChromaDB, sentence-transformers | Server libraries | Installed by pip under their own licences |
