# Session Handoff

## Goal
Android app for static parking-lot plate detection and OCR, tested on Alberta plates.

## Current Status
- Plate detection is working well enough to find and box plates live.
- Plate crop saving is working.
- OCR was previously done with ML Kit and was inaccurate on Alberta plates.
- OCR has now been switched to a pre-exported ONNX model.
- After latest testing on March 17, 2026, crop saving is still working and now triggers quickly again.
- The debug/status panel in the middle of the camera screen has been made collapsible so it does not cover most of the preview.
- The current ONNX OCR path no longer crashes the app, but it is not producing usable text and currently ends in `OCR unavailable`.

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

### Current OCR model
- Switched to pre-exported ONNX OCR model from:
  - `josephinekmj/anpr-models`
- Downloaded and added assets:
  - `app/src/main/assets/ocr/plate_ocr.onnx`
  - `app/src/main/assets/ocr/ppocr_keys_v1.txt`
- Added ONNX Runtime Android dependency:
  - `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
- Replaced ML Kit OCR implementation with:
  - `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt`
- Updated camera flow to use the new OCR engine in:
  - `app/src/main/java/com/andre/alprprototype/CameraActivity.kt`

## Important Current Unknown
- The new ONNX OCR path compiles and packages successfully.
- It now appears to execute on-device without crashing, but it still returns no usable OCR result on tested crops.
- This suggests the blocker is more likely the model itself and/or its expected preprocessing/output format, not just the app plumbing.

## Files Most Relevant Tomorrow
- `app/src/main/java/com/andre/alprprototype/CameraActivity.kt`
- `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/AlprPipeline.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/BestPlateCropSaver.kt`
- `app/src/main/java/com/andre/alprprototype/alpr/YoloTflitePlateCandidateGenerator.kt`
- `app/src/main/java/com/andre/alprprototype/ui/PlateDebugOverlayView.kt`
- `app/build.gradle.kts`

## Known Good Facts
- Plate detection/crop stage is mostly working.
- The detector now boxes the plate correctly live.
- The crop preview card now distinguishes:
  - waiting for first crop
  - OCR pending
  - OCR unavailable
- The camera analyzer and OCR path were hardened so OCR failures do not take down the app.
- The major remaining task is replacing the current OCR model or swapping to a better-supported recognizer path.

## Recommended Next Step
1. Replace the current `app/src/main/assets/ocr/plate_ocr.onnx` model instead of continuing to tune this one.
2. Prefer a PaddleOCR mobile recognizer path because it has stronger mobile deployment support and is a more realistic base for Android OCR.
3. If possible, use a recognizer fine-tuned on North American license plates rather than a generic OCR model.
4. After swapping models, re-check:
   - expected input shape/channel order
   - vocabulary indexing / blank token handling
   - output tensor layout for decoding
   - whether Alberta plate crops need a plate-specific fine-tune

## What Was Changed Today

### Stability / crash handling
- Moved OCR inference off the UI thread.
- Added guards around ONNX output decoding so unexpected tensor types/shapes do not crash the app.
- Hardened frame analysis so camera frames always close even if OCR/crop code throws.

### UI
- Made the large status/debug block collapsible from the camera screen.
- Fixed the crop caption state so it no longer says `Waiting for a saved crop` after a crop has already been saved.

### Outcome from latest manual test
- Cropped image capture is working again.
- The app is no longer crashing in the same way during OCR.
- OCR is still not producing text with the current model, so model replacement is the next task.
