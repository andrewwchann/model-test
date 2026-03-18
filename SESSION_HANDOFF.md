# Session Handoff

## Goal
Android app for static parking-lot plate detection and OCR, tested on Alberta plates.

## Current Status
- Plate detection is working well enough to find and box plates live.
- Plate crop saving is working.
- OCR was previously done with ML Kit and was inaccurate on Alberta plates.
- OCR has now been switched to a FastPlateOCR ONNX model with a matching config.
- After latest testing on March 17, 2026, crop saving is still working and now triggers quickly again.
- The debug/status panel in the middle of the camera screen has been made collapsible so it does not cover most of the preview.
- The current OCR path compiles and the model/config contract is now correct.
- Offline testing on manually cropped plate images is producing usable text.
- The likely remaining issue is pipeline latency and how quickly the app grabs a good crop, not the OCR model contract.

## What Was Fixed

### Android Studio / project setup
- Fixed `local.properties` SDK path escaping so Android Studio could open the project.

### Plate detection
- Added a real detector path using a YOLO-style TFLite plate detector.
- Downloaded weights from `yasirfaizahmed/license-plate-object-detection`.
- Exported to TFLite and added asset:
  - `app/src/main/assets/models/license_plate_detector.tflite`
- Added detector implementation:
  - `app/src/main/java/com/andre/alprprototype/alpr/YoloTflitePlateCandidateGenerator.kt`

### Detector bugs that were fixed
- Fixed scan scheduling bug where detector was always skipped when `scanEveryNFrames = 1`.
- Verified the TFLite detector produced reasonable boxes on local sample images:
  - `car-photos/car1.png`
  - `car-photos/car2.png`
- Found that live-camera bad boxes were caused by remapping a correct upright detection box back into source camera coordinates.
- Removed the bad remap for YOLO boxes and kept them in upright coordinates.
- Updated crop saving to crop directly from upright coordinates for YOLO detections.

### Debug instrumentation added
- Status card shows detector info and self-test summary.
- Overlay labels show:
  - `raw`
  - `flt`
  - `trk`
- Added detector self-test using bundled sample images:
  - `app/src/main/assets/debug-samples/car1.png`
  - `app/src/main/assets/debug-samples/car2.png`
- Self-test result observed on device:
  - `Self-test car1=141x102 c=0.83 | car2=138x74 c=0.85`
- This confirmed Android-side bitmap detection was fine and live-camera remap was the actual issue.

### Post-detector pipeline tuning
- Relaxed filtering and quality gating for `yolo-tflite` detections in:
  - `app/src/main/java/com/andre/alprprototype/alpr/AlprPipeline.kt`
- This made the app accept YOLO plate boxes more readily for static parked plates.

## OCR History

### ML Kit OCR phase
- OCR initially used generic ML Kit text recognition.
- Problems observed:
  - Read `Alberta` header instead of registration text.
  - Produced values like `ALBERTA2`.
  - Later got closer but still wrong, e.g. `ATAGRABH` instead of `GRABHER`.
- Tried improving ML Kit path by:
  - OCR on multiple sub-crops
  - center/lower-band bias
  - penalties for words like `ALBERTA`
- This improved behavior somewhat but was still not reliable enough.

### FastPlateOCR attempt
- Tried `fast-plate-ocr` ecosystem with checkpoint:
  - `autolane/cct-s-ocr-alpr`
- Downloaded:
  - `best_checkpoint.keras`
  - `model_config.yaml`
  - `plate_config.yaml`
- Tried exporting to TFLite and ONNX.
- Blockers:
  - export/tooling instability
  - unsupported/custom ops for mobile conversion
  - not a practical path for this repo right now

### Dead-end OCR model that was removed
- Tried pre-exported ONNX OCR model from:
  - `josephinekmj/anpr-models`
- Problem discovered offline:
  - model input was actually `NCHW [batch, 3, 48, width]`
  - model output was `[batch, time, 438]`
  - bundled `ppocr_keys_v1.txt` only had 38 entries
- Conclusion:
  - this was an asset-pair mismatch, not just a preprocessing bug
  - that OCR pair is not worth further tuning in this repo

### Current OCR model
- Switched to FastPlateOCR ONNX model and matching YAML config from:
  - `fastplateocr_assets/cct_xs_v2_global.onnx`
  - `fastplateocr_assets/cct_xs_v2_global_plate_config.yaml`
- Final app asset layout:
  - `app/src/main/assets/ocr/plate_ocr.onnx`
  - `app/src/main/assets/ocr/plate_config.yaml`
- ONNX contract confirmed offline:
  - input `uint8 [batch, 64, 128, 3]`
  - output `plate [batch, 10, 37]`
  - output `region [batch, 66]`
- YAML contract confirmed:
  - RGB
  - `img_width=128`
  - `img_height=64`
  - `keep_aspect_ratio=false`
  - alphabet `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_`
  - pad char `_`
- `PlateOcrEngine.kt` was rewritten around this exact contract:
  - YAML-driven config load
  - RGB hard resize to `128x64`
  - NHWC `uint8` tensor creation
  - fixed-slot argmax decode over 10 positions
  - strip trailing `_`
  - startup validation/logging for model I/O
- `copyAssetToCache` now always refreshes the cached ONNX file so stale models are not silently reused.

## Important Current Unknown
- The OCR model itself is now working offline on manual crops.
- The main remaining unknown is how fast the live pipeline grabs a good crop and triggers OCR on-device.
- Earlier device testing suggested the crop could be somewhat loose and the pipeline felt slow.
- Based on offline tests, OCR likely no longer needs a perfect ultra-tight crop, but timing and first-good-crop behavior still need validation.

## Files Most Relevant Tomorrow
- `app/src/main/java/com/andre/alprprototype/CameraActivity.kt`
- `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/AlprPipeline.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/BestPlateCropSaver.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/YoloTflitePlateCandidateGenerator.kt`
- `app/src/main/java/com/andre/alprprototype/ui/PlateDebugOverlayView.kt`
- `app/build.gradle.kts`
- `offline_fastplateocr_test.py`
- `fastplateocr-integration-checklist.md`
- `plan.md`

## Known Good Facts
- Plate detection/crop stage is mostly working.
- The detector now boxes the plate correctly live.
- The crop preview card now distinguishes:
  - waiting for first crop
  - OCR pending
  - OCR unavailable
- The camera analyzer and OCR path were hardened so OCR failures do not take down the app.
- FastPlateOCR is integrated as a matched model/config pair.
- Offline OCR on manual crops is working:
  - `car1` manual crop decoded as `GRABHER`
  - `car2` manual crop decoded as `U97105`
- Full-car images still decode to pad-only output, which is expected and confirms the recognizer wants a plate crop.
- A somewhat looser crop similar to earlier device output still read correctly offline.
- The major remaining task is performance/latency tuning, especially how quickly the first good crop is saved and OCR is triggered.

## Recommended Next Step
1. Measure on-device latency across:
   - detector
   - crop save
   - OCR request
   - OCR result
2. If crop capture feels slow, change crop-saving behavior so the first passing crop is saved immediately instead of waiting for a better score.
3. If needed, reduce OCR crop variants on-device to speed up first result delivery.
4. Re-test live camera behavior and compare saved crops against the manual/offline crops that decode correctly.
5. Only after latency is understood, tune crop margins or cleanup rules.

## What Was Changed Today

### OCR asset swap
- Replaced the broken Paddle-style OCR asset pair with FastPlateOCR assets.
- Added:
  - `app/src/main/assets/ocr/plate_ocr.onnx`
  - `app/src/main/assets/ocr/plate_config.yaml`

### OCR engine rewrite
- Rewrote `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt` to:
  - parse `plate_config.yaml`
  - validate ONNX input/output contract at startup
  - preprocess crops as RGB `128x64` NHWC `uint8`
  - decode `plate` head as fixed slots rather than CTC
  - ignore `region` head for now
  - log slot predictions and decoded output
- Updated asset caching so the newest ONNX asset is always copied into cache before session creation.

### Offline OCR validation
- Added local helper:
  - `offline_fastplateocr_test.py`
- This helper validates the model contract and runs OCR on image files or directories using the same FastPlateOCR assumptions as the Android app.
- Results observed:
  - full-car sample images returned only pad characters, as expected
  - manually cropped `car1` plate decoded as `GRABHER`
  - manually cropped `car2` plate decoded as `U97105`

### Outcome from latest manual/offline test
- OCR is no longer blocked on model preprocessing or decode format.
- The new OCR model can read realistic plate crops offline.
- The next likely bottleneck is live pipeline speed and how quickly the app saves a good crop for OCR.
