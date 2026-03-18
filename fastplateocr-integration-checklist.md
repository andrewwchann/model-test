# FastPlateOCR Integration Checklist

## Goal

Replace the current broken OCR asset pair with the matched FastPlateOCR ONNX model and config, while leaving the existing detector, crop saver, and camera trigger flow intact.

## Asset bundle to use

Use the new FastPlateOCR bundle as the only OCR source:

- `cct_xs_v2_global.onnx`
- `cct_xs_v2_global_plate_config.yaml`

Copy them into:

```text
app/src/main/assets/ocr/
```

Use stable names if desired, but keep the ONNX and YAML paired together.

---

## Integration scope

### Change only:
- `app/src/main/assets/ocr/`
- `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt`

### Do not change:
- detector
- crop saver
- `CameraActivity` OCR trigger flow
- debug overlay
- live detection pipeline

---

## Implementation checklist

### 1. Replace the OCR assets
Copy the FastPlateOCR files into:

```text
app/src/main/assets/ocr/
```

Recommended final layout:

```text
app/src/main/assets/ocr/
    plate_ocr.onnx
    plate_config.yaml
```

If you rename them, update the asset-loading code accordingly.

---

### 2. Parse the YAML config and treat it as authoritative
Update `PlateOcrEngine.kt` to load `plate_config.yaml` and use it as the inference contract.

Read at least:

- `img_height`
- `img_width`
- `image_color_mode`
- `alphabet`
- `pad_char`
- `max_plate_slots`
- `keep_aspect_ratio`

Do not hardcode the alphabet string in Kotlin if it already exists in the YAML.

---

### 3. Add startup validation and logging
At OCR engine startup, log:

- input names
- output names
- input shapes
- output shapes
- model input type
- YAML values used for inference
- alphabet length
- max plate slots
- pad char

Fail fast if any of the following are wrong:

- model input is not compatible with `64x128x3`
- model input type is not `uint8`
- output `plate` is missing
- output `region` is missing
- `alphabet.length` does not equal the number of `plate` output classes
- `max_plate_slots` does not equal the number of plate positions in the model output

Expected contract for the current uploaded model:

- input: `[batch, 64, 128, 3]`
- output `plate`: `[batch, 10, 37]`
- output `region`: `[batch, 66]`

---

### 4. Rewrite preprocessing for FastPlateOCR
Remove the old Paddle-style OCR preprocessing.

New preprocessing should:

- load the crop image
- convert to RGB
- resize to `128x64`
- use NHWC / channels-last layout
- pack pixels as `uint8`
- create an input tensor shaped like `[1, 64, 128, 3]`

Do not use:
- grayscale conversion
- NCHW packing
- float normalization intended for PaddleOCR
- old `1x1x70x140` assumptions

---

### 5. Replace the decoder with fixed-slot decode
Remove the old CTC-style decoder.

New decoding should:

- read the `plate` output head
- iterate over each of the 10 plate slots
- take `argmax` over the 37 classes for each slot
- map class indices using the YAML `alphabet`
- strip trailing pad chars using the YAML `pad_char`
- return unavailable if the decoded result is blank after stripping pads

For this model, treat `_` as padding, not as a real character.

---

### 6. Ignore the `region` head for the first pass
Do not use the `region` output yet.

You only need the `plate` output to get OCR text working.

Still validate that the `region` head exists so you know the correct model was loaded.

---

### 7. Keep postprocessing minimal
After decode:

- strip trailing pad chars
- trim whitespace if any
- return unavailable if empty

Do not add aggressive cleanup yet.
Do not broadly strip characters unless there is a clear need.
The model alphabet is already constrained.

Uppercasing is unnecessary because the model alphabet is already uppercase.

---

### 8. Add per-slot confidence logging for debugging
For each of the 10 slots, log:

- predicted character
- class index
- argmax confidence

Also log:

- raw decoded string before pad stripping
- final decoded string after pad stripping

This will help distinguish:
- bad crops
- weak predictions
- preprocessing bugs
- decode bugs

---

### 9. Add an offline saved-crop OCR test path
Before device testing, add a debug helper that runs OCR on saved crop files and prints results.

Purpose:
- deterministic testing without live camera variability
- easier debugging of preprocessing and decode
- easier comparison across crop variants

Suggested flow:
- point the helper at a directory of saved plate crops
- run OCR over each file
- print filename + decoded result + optional confidence summary

---

### 10. Remove old OCR assumptions
Once the new engine is working, remove:

- old Paddle-specific preprocessing
- old CTC decoder logic
- old dictionary dependency
- any hardcoded grayscale OCR assumptions

Do not remove ML Kit yet unless you want to clean dependencies after FastPlateOCR is stable.

---

## Recommended implementation order

1. Copy assets into `app/src/main/assets/ocr/`
2. Add YAML parsing
3. Add startup validation and model I/O logging
4. Rewrite preprocessing for RGB `64x128` NHWC `uint8`
5. Implement fixed-slot decode using YAML alphabet and pad char
6. Add offline saved-crop OCR helper
7. Compile and run startup validation
8. Test on saved crops
9. Test live camera OCR
10. Tune crop variants or cleanup rules only after the above is working

---

## Success criteria

The integration is successful when:

- the app loads the ONNX and YAML without errors
- startup validation confirms the model contract matches the YAML
- OCR returns non-empty strings on saved crop images
- decoded text uses the expected plate alphabet
- blank or invalid outputs are handled cleanly
- live camera OCR works without changing detector or crop flow

---

## Anti-goals

Do not do these in the first pass:

- do not tune detector thresholds
- do not redesign the crop pipeline
- do not integrate region classification into UI logic
- do not add complex cleanup heuristics
- do not debug the old Paddle asset pair any further

---

## One-line summary

Swap in the matched FastPlateOCR ONNX + YAML bundle, rewrite `PlateOcrEngine.kt` around the model's true NHWC `uint8` fixed-slot contract, keep the rest of the app unchanged, and validate on saved crop images before live testing.
