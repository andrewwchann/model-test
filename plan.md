# FastPlateOCR Integration Plan

## Goal

Replace the current broken OCR asset pair with the matched FastPlateOCR ONNX model and config, while leaving the existing detector, crop saver, and camera trigger flow intact.

## Steps

1. Replace the OCR asset source
Use the new FastPlateOCR bundle as the only OCR source:
- `fastplateocr_assets/cct_xs_v2_global.onnx`
- `fastplateocr_assets/cct_xs_v2_global_plate_config.yaml`

2. Move the assets into the app
Copy them into `app/src/main/assets/ocr/` with stable names the app will load.

3. Rewrite `PlateOcrEngine`
Update `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt` to:
- load the FastPlateOCR model instead of the old Paddle model
- read the YAML config
- use fixed input `[1, 64, 128, 3]`
- use `uint8` input tensors
- pack pixels as RGB in `NHWC`

4. Replace the decoder
Remove the old CTC-style logic and implement fixed-slot decode:
- read output `plate`
- for each of 10 positions, take `argmax`
- map indices with `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_`
- strip trailing `_`

5. Ignore the `region` head for now
Do not use the `[batch, 66]` `region` output in the first pass. It is not needed to get text working.

6. Keep the rest of the app unchanged
Do not touch:
- detector
- crop saver
- `CameraActivity` OCR trigger flow
- debug overlay

7. Add strict validation and logging
At OCR engine startup, log:
- input/output names
- input/output shapes
- YAML config values
- alphabet length versus model class count

Fail fast if:
- YAML alphabet length does not match plate output classes
- model input is not `uint8`
- model shape is not compatible with `64x128x3`

8. Keep postprocessing minimal
After decoding:
- uppercase
- strip trailing pad chars
- remove non-alphanumeric characters if needed
- keep the existing `unavailable if blank` behavior

9. Add an offline test path
Before device testing, add a local helper or debug method that runs OCR on saved crop files and prints decoded text. This gives a deterministic way to validate the engine without live camera input.

10. Clean up old OCR assumptions
Once the new engine is in:
- remove Paddle-specific preprocessing and shape assumptions
- remove the old dictionary dependency
- optionally remove ML Kit later, but not as part of the OCR swap

## Implementation Order

1. Copy assets into app assets.
2. Rewrite `PlateOcrEngine` for FastPlateOCR preprocessing and decode.
3. Add startup validation and logging.
4. Add offline saved-crop OCR test path.
5. Compile.
6. Test on saved crops when available.
7. Only then tune crop variants or cleanup rules.
