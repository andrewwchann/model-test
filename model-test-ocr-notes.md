# Android OCR Integration Notes for `model-test`

## Current repo status

Your app is already past the early wiring stage.

What is working:
- plate detection is working
- crop saving is working
- the camera flow is calling OCR
- OCR preprocessing was corrected from grayscale `1x1x70x140` toward the model's actual expected input format

What is still blocked:
- OCR decoding is fundamentally broken because the current OCR model and dictionary do not match

## Root cause

The current OCR asset pair is incompatible.

### What was wrong
- `PlateOcrEngine.kt` was feeding the ONNX model as grayscale `[1, 1, 70, 140]`
- offline inspection showed the model actually expects NCHW `[batch, 3, 48, width]`
- the model output is `[batch, time, 438]`
- the bundled `ppocr_keys_v1.txt` has only 38 entries
- even with corrected preprocessing, the decoder cannot map the model outputs to the right characters

## The real takeaway

This is not just a preprocessing bug.

It is an **asset-pair mismatch**:
- preprocessing was wrong
- but the bigger blocker is that the OCR model and dictionary do not match

That means more decoder tuning will not fix the current OCR asset pair.

---

## Part 1: Checklist for choosing a compatible Android-friendly OCR model/dictionary pair

### 1. The model and dictionary must come from the same export
This is the most important rule.

If the model outputs `C` classes per timestep, then:
- the dictionary must match those classes exactly
- the blank token convention must also match

A `438`-class recognizer and a `38`-entry dictionary are not a usable pair.

### 2. Prefer plate-specific OCR over generic OCR
For ALPR, a plate-specific recognizer is usually the better fit.

Why:
- your app already crops plate regions first
- plate OCR has a constrained output space
- plate-specific recognizers are often easier to decode and clean up

### 3. Verify the exact model input contract before integration
Before writing Android preprocessing, inspect:
- input tensor name
- output tensor name
- input shape
- number of channels
- fixed width vs dynamic width
- normalization rules
- output tensor layout
- blank index
- decoding style (usually CTC)

Do not assume shape rules from old code.

### 4. Prefer a mobile-friendly runtime
For Android, ONNX Runtime is a good choice if:
- you already use it
- the model exports cleanly
- the recognizer runs fast enough on-device

### 5. Keep the charset constrained when possible
For a North American plate reader, a smaller charset is often better:
- `A-Z`
- `0-9`
- only extra symbols if the model truly needs them

A very large generic OCR vocabulary is often unnecessary for license plates.

### 6. Make sure the export path is practical
Do not choose a model if:
- you cannot verify its input/output contract
- you do not have its matching dictionary
- you cannot reproduce preprocessing and decoding cleanly on Android

### 7. Decide now: generic OCR or strict plate OCR
Use this split:

**Generic OCR**
- more flexible
- more likely to read arbitrary text
- usually more setup complexity

**Plate-specific OCR**
- better aligned with your app
- simpler cleanup
- usually the better first choice for ALPR

### Recommendation
For this app, use **plate-specific OCR first**.

---

## Part 2: Concrete plan for your current repo

## Recommended direction

Keep:
- your current plate detector
- your current crop pipeline
- your current CameraActivity OCR trigger flow

Replace:
- the current OCR asset pair

Refactor:
- `PlateOcrEngine` so it follows the new model contract exactly

## Best practical path

### Step 1: Replace the OCR assets with a matched pair
Use a self-consistent OCR bundle such as:
- `plate_ocr.onnx`
- `charset.txt`
- `model_config.json`

Do not mix assets from different model sources.

### Step 2: Add explicit OCR metadata
Create an asset config file so your code does not hardcode model assumptions.

Example:
```json
{
  "input_channels": 3,
  "input_height": 48,
  "input_width": 168,
  "dynamic_width": false,
  "color_order": "RGB",
  "layout": "NCHW",
  "normalize": "0_1",
  "decoder": "CTC",
  "blank_index": 0,
  "vocab_file": "ocr/charset.txt"
}
```

### Step 3: Make `PlateOcrEngine` inspect the ONNX contract at startup
At initialization, log:
- `session.inputInfo`
- `session.outputInfo`
- input names
- output names

This gives you a hard sanity check before debugging OCR text.

### Step 4: Remove old hardcoded assumptions
Delete assumptions like:
- grayscale-only input
- fixed `[1,1,70,140]`
- direct dependence on the old `ppocr_keys_v1.txt`

### Step 5: Split `PlateOcrEngine` into clean stages
Refactor it into:
1. load metadata and vocabulary
2. inspect session input/output
3. preprocess exactly to model contract
4. run inference
5. decode exactly to model contract
6. apply plate-specific cleanup

### Step 6: Keep postprocessing simple
After decode:
- uppercase
- strip non-alphanumeric characters where appropriate
- clamp to a reasonable plate length
- optionally bias toward known Alberta plate patterns

Do not rely on postprocessing to rescue a bad model.

### Step 7: Leave CameraActivity mostly unchanged
Your OCR call path is already in the right place:
- detect plate
- save crop
- invoke OCR
- update UI/debug state

That part does not appear to be the blocker.

---

## If you want to stay with PaddleOCR-style recognizers

This is still possible, but only if the assets are matched.

You must have:
- recognizer model
- matching dictionary
- correct input shape
- correct normalization
- correct decoder convention

Checklist:
- confirm whether input is `1x3x48xW` or `1x3x48x320`
- confirm output shape is something like `NxTxC`
- confirm blank index
- confirm duplicate-collapse rules
- confirm the dictionary is the exact one used for training/export

Do not keep using `ppocr_keys_v1.txt` unless it is proven to be the correct dictionary for that exact model.

---

## Concrete repo edits to do next

### 1. Stop shipping the current broken OCR asset pair
The current model + dictionary combination is not worth more debugging.

### 2. Add a model config file under assets
Store:
- channels
- height
- width
- dynamic width flag
- normalization
- decoder type
- blank index
- vocabulary file path

### 3. Update `PlateOcrEngine`
Make it:
- read config
- read vocab
- inspect model I/O at startup
- preprocess exactly
- decode exactly
- log class counts and safety checks

### 4. Add validation guards
At startup:
- fail fast if output class count does not match expected dictionary size + blank convention
- log a clear error when assets are mismatched

### 5. Validate with saved crops first
Before tuning live camera behavior, test on a batch of saved cropped plate images.

Suggested workflow:
- collect 20 to 30 crops
- run OCR over them offline or in-app
- compare predictions manually
- only then adjust live thresholds or cleanup rules

### 6. Clean up dependencies later
If you fully switch to ONNX OCR, you can remove ML Kit text recognition later unless you want to keep it as a fallback baseline.

---

## Recommendation ranking

### Best fit
1. plate-specific OCR model with ONNX Runtime on Android

### Second-best
2. matched PaddleOCR recognizer plus matching dictionary

### Worst option
3. keep debugging the current mismatched `plate_ocr.onnx` + `ppocr_keys_v1.txt` pair

---

## What I would do next

1. pick a new OCR asset pair
2. keep detector and crop flow unchanged
3. add a metadata-driven OCR engine
4. validate on saved crops first
5. only then tune live camera OCR behavior

---

## One-line summary

Your repo is already structurally ready for OCR, but the current OCR assets are incompatible, so the next real step is to replace them with a matched model-and-dictionary pair and make `PlateOcrEngine` read the model contract instead of hardcoding assumptions.
