# Native source package

The APK contains prebuilt native code that includes GPL-3.0-or-later espeak-ng. This
package holds the source for that native code, so it is published next to every APK
under GPLv3 section 6. Verbatim's own source is the tagged commit of this repository.

Whistle and the closed-source Needle runtime are no longer built, loaded or bundled.

## How the shipped library was built

`libsherpa-onnx-jni.so` comes from the official sherpa-onnx AAR, unmodified:

| Step | Artifact | Built from |
|---|---|---|
| Final AAR | `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`, SHA-256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` (tag v1.13.8), workflow `.github/workflows/android-static.yaml`, [run 34486043621](https://github.com/k2-fsa/sherpa-onnx/actions/runs/34486043621) |
| Static ONNX Runtime | `onnxruntime-android-arm64-v8a-static_lib-1.28.2.zip`, SHA-256 `bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0` | [csukuangfj/onnxruntime-build](https://github.com/csukuangfj/onnxruntime-build) commit `6314c7b252578dc0b8ddd3919b593c4359812818`, [run 33741118129](https://github.com/csukuangfj/onnxruntime-build/actions/runs/33741118129), building ONNX Runtime tag v1.28.2, commit `33ca9628233dc8f002435e868d4c2e9f82766ca1` |

Evidence for each link:

- GitHub's digest for the AAR release asset equals the pin in `prepare_tts.py`, and
  the successful tag run built it.
- sherpa's `cmake/onnxruntime-android-aarch64-static.cmake` pins the runtime zip's hash.
- That zip was uploaded at 2026-09-03T10:21:26Z, inside the upload step of the runtime
  run above (10:21:25 to 10:21:32).
- Build paths embedded in the library name both projects.

For this Android build, `build-static_lib.sh` changes the ONNX Runtime tree in one way:
it deletes the `SOVERSION` line of `cmake/onnxruntime.cmake`. ONNX Runtime's own patches
are in its `cmake/patches` directory, inside `onnxruntime.zip`.

Key build settings are in sherpa's `build-android-arm64-v8a.sh` (with `BUILD_SHARED_LIBS=OFF`)
and the runtime workflow's `CMAKE_OPTIONS` (`-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21
-DANDROID_STL=c++_shared -Donnxruntime_BUILD_UNIT_TESTS=OFF`). Both workflows use the
GitHub runner's latest Android NDK.

To rebuild: extract the archives, run the ONNX Runtime recipe, then sherpa's script with
`SHERPA_ONNXRUNTIME_LIB_DIR` and `SHERPA_ONNXRUNTIME_INCLUDE_DIR` pointing at the result.
Each CMake fetch accepts a local file, so it can use the archives here. Replace
`.local-models/tts/sherpa-onnx.aar` with your AAR, or build one with
`android/SherpaOnnxAar` in the sherpa source.

## What is in the package

`native_sources.json` lists 51 archives with URLs and SHA-256 hashes (about 780 MB):

- **sherpa-onnx and what it fetches:** kaldi-native-fbank, kissfft, kaldi-decoder, kaldifst,
  Eigen 3.4.0, OpenFst, simple-sentencepiece, nlohmann/json, piper-phonemize, hclust-cpp, espeak-ng.
- **ONNX Runtime, its build recipe, and every source in its `cmake/deps.txt`.** This is
  deliberately more than an Android CPU build compiles. Only the prebuilt `protoc` host
  tools are left out; the protobuf source is included.

Two entries are included because the binary doesn't show which revision was compiled:

- espeak-ng: sherpa's pin `ed530aa` and the piper-phonemize default `7251208`.
- OpenFst: sherpa's `v1.8.5-2026-07-09` and kaldifst's `v1.8.5-2026-04-10`.

`android_dependencies.txt` lists the Java/Kotlin libraries in the APK, with exact
versions. Their source is published with them on Google Maven and Maven Central.
Speech and voice models are data, not program source; they stay under their own
licences (see `THIRD_PARTY_NOTICES.md`).

## Building the package (maintainers)

```
python -I tools/prepare_native_sources.py <directory-outside-the-repository> <release-version>
```

This verifies every archive, then writes `verbatim-<version>-native-sources.tar` for the
GitHub Release. It never extracts or runs downloaded source, and it reuses verified
files. Do not commit the output. Keep the release asset available for as long as the
APK is offered, even if an upstream project removes an archive.

## What was not done

- Nobody rebuilt the library from this package and compared the result. GitHub's run
  logs need a signed-in account and were not read, so the runtime link rests on the
  timing above.
- This is release engineering, not legal advice, and not a native privacy audit.
- Never include a release-signing key or password. A self-built app is signed with
  your own key, and Android then treats it as a different signer: uninstall first.
