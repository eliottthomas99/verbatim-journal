# Native source and binary-release checklist

Whistle and the closed-source Needle runtime are no longer built, loaded or
bundled. Transcription uses Parakeet through sherpa-onnx; Piper also uses sherpa.
This removes the identified unavailable-source component, not the obligations
of the remaining GPL-covered espeak-ng combination.

## What is pinned

- sherpa-onnx 1.13.8: commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.
  The AAR checksum is pinned in `prepare_tts.py`.
- espeak-ng fork: `ed530aa113046142eb5115cf2fc9157854d0ffe1`, from that
  sherpa revision's `cmake/espeak-ng-for-piper.cmake`.
- ONNX Runtime 1.28.2: source commit
  `33ca9628233dc8f002435e868d4c2e9f82766ca1`, the version selected by sherpa's
  Android build script. Verifying the prebuilt Android runtime's exact build
  provenance/patches remains part of the release review.
- Other sherpa dependency archives and kissfft are pinned in
  `native_sources.json`, using checksums from upstream CMake declarations.
- Full native licence texts, including ONNX Runtime's third-party notices,
  are pinned in `native_licenses.json` and bundled by `prepare_licenses.py`.
  Voice model cards and the original upstream notices are kept with the voices.

## Source collection (maintainers only)

```
python -I tools/prepare_native_sources.py <directory-outside-the-repository>
```

This fetches about 326 MB of source archives, not speech/language-model weights.
It verifies SHA-256 and never extracts or executes downloaded source. It can
reuse an existing verified collection. Do not commit this directory or advertise
it as complete corresponding source yet.

## Before an APK is distributed

- Confirm the exact source, patches and build flags corresponding to the
  prebuilt sherpa AAR and its statically linked ONNX Runtime. The source archive
  collection includes sherpa's Android scripts and AAR release workflow.
- Finish dependency closure: ONNX Runtime's `cmake/deps.txt` lists additional
  sources; archive all applicable sources (including needed submodules) and
  build/install scripts. Listing URLs or including third-party notices is not
  itself proof that complete corresponding source has been supplied.
- Include the matching Verbatim source, the native sources and build directions,
  and any installation information required by the distribution arrangement.
  Offer equivalent access next to the APK under GPLv3 section 6; maintain that
  access even if upstream removes an archive. Ordinary Android build tools are
  prerequisites; never include a private release-signing key or password.
- Preserve copyright/attribution and applicable licence texts for code, voices
  and model conversions. Do not assume a dataset licence alone settles every
  model-provenance question.
- Verify the final APK contains no Needle library or Whistle assets, has the
  full licences, and passes signed-device recording/transcription/playback and
  read-aloud tests. Back up the signing key outside the development laptop.

This is a release engineering checklist, not legal advice or a claim that a
complete native legal/privacy audit has been performed. APK publication stays
pending until the corresponding-source review and remaining release checks
are complete; local/private testing can continue.
