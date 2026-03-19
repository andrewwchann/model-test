# Data Quality Improvement Plan for Google Sheets Training Uploads

## Goal

Improve training-data quality without breaking the current app flow.

Success means:
- fewer false uploads from monitors, screens, and unstable detections
- good real-world plates still upload reliably
- threshold tuning becomes easier because rejection reasons are visible

This plan is intentionally biased toward **precision over recall**.

---

## Current constraints in this repo

The current upload path is:

1. analyzer saves a crop
2. `CameraActivity` asks `PlateOcrEngine` to OCR that crop
3. OCR callback returns later on a different timing path
4. `CameraActivity` uploads if text is non-blank and the crop path has not already been uploaded

Important implication:

- upload gating cannot safely depend on "whatever the latest frame is right now"
- upload decisions must use data that is explicitly carried alongside the saved crop / OCR result

The current tracker also has no real "lost track" state. If detections disappear, the track age keeps increasing. That means "high age" does **not** currently prove stability.

The current Google Sheets schema is also narrow. If we want richer upload metadata later, the Apps Script and the sheet columns must change too.

---

## What to implement first

Implement the work in this order:

1. add better upload gating in `CameraActivity`
2. add a plate plausibility filter
3. add structured rejection logging
4. tighten crop-saving thresholds modestly
5. improve tracker continuity and reset behavior
6. only then revisit OCR score thresholds
7. extend Google Sheets schema later if needed

This order matters because the first three items improve data quality immediately without requiring risky refactors.

---

## Phase 1: Safe gating improvements

### 1. Gate uploads on explicit stable signals only

Do not upload on "any non-blank OCR result".

Instead, create a small helper in `CameraActivity`, for example:

- `evaluateTrainingUpload(frameState, cropPath, ocrResult): UploadDecision`

That helper should only use data already available at upload time:

- `cropPath`
- OCR text
- OCR confidence
- `frame.state.emittedResult`
- `frame.state.activeTrack`
- `frame.state.quality`

Required first-pass checks:

- crop file exists
- OCR text is non-blank
- OCR text matches `emittedResult.text`
- `emittedResult.supportingFrames >= 3`
- `quality.passes == true`
- text passes plausibility rules
- crop path has not already been uploaded

Important rule:

- if OCR text and emitted final text disagree, do not upload

This is the cleanest immediate upgrade because it requires agreement between:

- saved crop OCR
- pipeline multi-frame vote
- crop quality gate

### 2. Add a plate plausibility filter

Create a small helper near the upload gate:

- `isPlausiblePlateText(text: String): Boolean`

Use simple generic rules first:

- length between 5 and 8
- only `A-Z` and `0-9`
- not all the same character
- reject strings where one character dominates almost the entire string

Do not add Alberta-specific formatting rules yet.

### 3. Add structured rejection logging

Whenever a crop is considered for upload, log:

- crop path
- OCR text
- OCR confidence
- final voted text
- supporting frames
- track age
- quality score
- rejection reason

Suggested rejection reasons:

- `blank_ocr`
- `duplicate_crop`
- `missing_final_result`
- `ocr_final_mismatch`
- `insufficient_support`
- `implausible_text`
- `quality_reject`
- `low_ocr_confidence`

This should be log-only first. No schema change required.

---

## Phase 2: Modest capture tightening

### 4. Tighten crop-saving thresholds conservatively

Do not jump straight to aggressive values.

Current values in `BestPlateCropSaver.kt`:

- `MIN_SCORE_IMPROVEMENT = 0.02f`
- `MIN_REACQUIRE_SCORE = 0.45f`
- `MIN_TRACK_AGE_FIRST_SAVE = 3`
- `MIN_TRACK_AGE_REACQUIRE = 2`

Safer first-pass values to try:

- `MIN_SCORE_IMPROVEMENT = 0.03f`
- `MIN_REACQUIRE_SCORE = 0.55f`
- `MIN_TRACK_AGE_FIRST_SAVE = 4`
- `MIN_TRACK_AGE_REACQUIRE = 3`

Reason:

- this reduces obviously early saves
- but it is less likely to kill valid real-world captures than the harsher draft values

Do not tighten further until live testing shows uploads are still too noisy.

### 5. Tighten YOLO candidate filtering slightly

The current YOLO path mostly checks width.

Add small first-pass filters before tracking:

- minimum width around `10%` of frame width
- aspect ratio roughly `2.0f..6.5f`
- reject boxes too close to extreme left/right edges

Keep this conservative. The detector already works in real-world tests, so avoid over-pruning.

---

## Phase 3: Tracker correctness before tracker strictness

### 6. Fix tracker continuity and reset behavior

Do not add IoU continuity alone.

First add proper track lifecycle behavior:

- when detections disappear for enough frames, clear the active track
- when a new detection is too far from the current track, either:
  - start a fresh track with low age
  - or reject the jump

Recommended first-pass additions:

- lost-track frame limit
- IoU continuity check
- reset age when continuity fails

Suggested starting point:

- `IOU_CONTINUITY_THRESHOLD = 0.30f`
- lost-track timeout around `2` to `4` scan updates

Important rule:

- a stale track must not continue accumulating age forever

Until this is fixed, `track.ageFrames` is not a trustworthy stability signal.

---

## Phase 4: Confidence tuning after observation

### 7. Do not hardcode a new OCR threshold until logging data

The current OCR confidence is not calibrated probability.

So instead of starting with a hard proposal like `0.85`, do this:

1. log OCR confidence for accepted and rejected real-world captures
2. review a batch of correct and incorrect uploads
3. choose the threshold from actual score distribution

Suggested workflow:

- capture 30 to 50 real-world samples
- note which were correct
- inspect their confidence values
- set threshold only after seeing the real range

Practical expectation:

- the right threshold may be much lower than `0.85`
- especially if the current score remains an internal heuristic

### 8. Add OCR variant agreement later, not first

Variant agreement is a good idea, but it requires `PlateOcrEngine` to expose more detail than it does now.

Defer this until after:

- upload gating
- plausibility filtering
- tracker reset
- rejection logging

When implemented, expose:

- best text
- best score
- second-best score
- count of agreeing variants

Then gate uploads on either:

- at least 2 variants agreeing
- or a strong margin between best and second-best

---

## Phase 5: Metadata expansion later

### 9. Extend Google Sheets only after the local gating is working

Do not start by changing the sheet schema.

First prove that local upload quality is improved.

After that, if needed, add new metadata fields such as:

- `trusted_auto`
- `review_candidate`
- `supporting_frames`
- `quality_score`
- `ocr_confidence`
- `rejection_reason` for local logs only, not uploaded rows

If you add any new uploaded fields, update all three:

- Android payload
- Apps Script
- Sheet header row

Do not change only one layer.

---

## Concrete file plan

### `app/src/main/java/com/andre/alprprototype/CameraActivity.kt`

Add:

- upload decision helper
- plausibility helper
- structured logging for acceptance / rejection

Keep:

- OCR request flow
- crop preview flow

### `app/src/main/java/com/andre/alprprototype/TrainingLogUploader.kt`

Keep upload transport simple.

Add only:

- better debug logs if needed

Do not push major business logic down into this class. It should remain mostly transport plus status reporting.

### `app/src/main/java/com/andre/alprprototype/alpr/BestPlateCropSaver.kt`

Adjust thresholds modestly.

### `app/src/main/java/com/andre/alprprototype/alpr/AlprPipeline.kt`

Implement:

- tracker reset/lost-track logic
- continuity-aware tracking
- slight candidate-filter tightening

Do not redesign the whole pipeline.

### `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt`

Leave OCR confidence math alone in the first pass unless there is a separate measured reason to change it.

If you later add variant agreement, do it by exposing more OCR decision detail rather than silently changing score meaning again.

### `apps-script-training-logger.gs`

Do not change in the first pass unless you intentionally expand metadata.

---

## Acceptance criteria

The first pass is successful when:

1. uploads require agreement between OCR text and stable final pipeline result
2. obviously invalid plate strings stop reaching Sheets
3. monitor/screen false positives drop noticeably
4. real-world stable plates still upload consistently
5. logs clearly explain why a candidate was rejected

The second pass is successful when:

1. tracker hops are reduced
2. crop saves happen later and cleaner
3. threshold tuning is based on observed real-world data, not guessed numbers

---

## Anti-goals

Do not do these first:

- do not redesign OCR scoring again without measurement
- do not use monitor tests as the main acceptance test
- do not add region-specific regexes immediately
- do not expand the Google Sheets schema before local gating works
- do not optimize for upload volume

---

## Recommended first implementation slice

If implementing now, start with exactly this:

1. add `UploadDecision` helper in `CameraActivity`
2. require `emittedResult` presence and text match
3. require `supportingFrames >= 3`
4. require plausible text
5. add structured rejection logs

That slice gives the highest data-quality improvement for the lowest code risk.
