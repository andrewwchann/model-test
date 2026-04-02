# Bad / Dirty Plate Detection Plan

## Problem

Current failure mode:

- For very dirty or low-contrast plates, the detector never returns a usable plate candidate.
- Because there is no detection, the app never creates a tracked plate, never saves a crop, and OCR never runs.
- That means the system has no graceful fallback path for "human can see a likely plate, model cannot confidently isolate it."

Observed from the examples in [`car-photos/bad/bad-car1.jpg`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/car-photos/bad/bad-car1.jpg) and [`car-photos/bad/bad-car2.jpg`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/car-photos/bad/bad-car2.jpg):

- The plate is still visually present.
- The main issue is not just OCR accuracy. It is detector recall.
- Dirt reduces edge contrast, plate borders, and character visibility enough that the current detector path likely drops below threshold before crop/OCR.

## Key conclusion

The system needs an explicit **uncertain-detection workflow**.

The right mental model is:

- `confident detection -> automatic crop -> OCR -> normal flow`
- `uncertain detection -> operator-assisted targeting -> OCR attempt -> manual plate entry if still uncertain`

Manual input should not be the first path. It should be the fallback after the system has tried:

1. normal automatic detection
2. operator-assisted region targeting
3. OCR on the operator-targeted crop

## Assessment of the tap-to-focus idea

The idea is directionally right, but "tap to focus" by itself is too narrow.

Why:

- Camera autofocus helps sharpness, but these bad examples are not mainly a focus problem.
- The bigger issue is that the app does not know which region to crop when the detector returns nothing.
- If we only add autofocus and keep the current detection gate, we still may not get a crop.

So the better feature is not just tap-to-focus. It is:

## Recommended feature: Tap-to-target manual ROI mode

Meaning:

- The operator taps the preview where the plate is.
- The app treats that tap as a hint to create a region of interest around the tapped point.
- The app crops that region directly even if the detector failed.
- Then the app tries OCR on that assisted crop.
- If OCR is still weak or blank, prompt the operator for manual plate entry.

This solves the real bottleneck: **no detection means no crop**.

Autofocus can still be added as part of the same gesture flow, but it should be secondary to ROI creation.

## Recommended execution path

### Route A: Best short-term path

Build an uncertain-detection fallback with operator-assisted ROI and manual entry.

Why this is the best route:

- It works with the current codebase and current model.
- It addresses the exact failure mode in this session.
- It improves field usability immediately.
- It does not require retraining before we get a usable workflow.

### Route B: Lower detector thresholds only

Possible, but not enough on its own.

Why it is risky:

- Lowering thresholds may increase false positives.
- Very dirty plates may still not produce useful detections.
- Even if we get a box, it may be poor enough to produce bad OCR and unstable behavior.

Use this only as a small tuning pass, not the primary solution.

### Route C: Retrain / fine-tune detector on dirty plates

This is the best long-term model improvement, but not the best first move for this session.

Why:

- It requires labeled dirty-plate examples.
- It takes longer to validate.
- It still benefits from having a manual fallback when the model fails in production.

Recommendation:

- Do this later, after the assisted fallback exists and after we collect more failure examples.

## Proposed product behavior

### Normal mode

- Pipeline runs as it does now in [`AlprPipeline.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/alpr/AlprPipeline.kt).
- If a plate is detected, tracked, quality-approved, and cropped, OCR runs as it does now in [`PlateOcrEngine.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt).

### Uncertain-detection trigger

Enter uncertain mode when:

- no detection has been found for N consecutive frames while the operator is viewing a likely rear vehicle scene, or
- the detector produces very weak / unstable candidates but never reaches a quality-passing crop, or
- the operator explicitly taps "Target Plate" / taps preview because auto-detection is not working.

For the first implementation, keep this simple:

- If there is no saved crop after a short detection window, show an operator action:
  `Plate not confidently detected. Tap plate area or enter manually.`

### Assisted targeting mode

- Operator taps near the plate in the preview.
- App maps tap coordinates into preview/image coordinates.
- App creates a default plate-shaped crop centered on the tap.
- App optionally drives autofocus/metering to the tapped area.
- App saves that assisted crop and sends it to OCR.

### OCR result handling in uncertain mode

- If OCR returns strong enough text, continue normal validation flow.
- If OCR is blank or weak, show manual entry dialog.
- Manual entry should preserve the assisted crop and vehicle evidence for auditability.

### Manual entry mode

Prompt should be explicit:

- `Plate could not be read reliably. Enter plate manually?`

Manual entry should capture:

- entered plate text
- source = `manual_entry`
- whether assisted crop existed
- OCR result if any
- confidence = null or a dedicated manual flag

This matters because downstream logic should know the plate was not machine-read.

## Why this fits the current code

Current architecture already separates:

- frame analysis in [`PlateFrameAnalyzer.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/PlateFrameAnalyzer.kt)
- crop saving in [`BestPlateCropSaver.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/alpr/BestPlateCropSaver.kt)
- OCR in [`PlateOcrEngine.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt)
- UI/session flow in [`CameraActivity.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/CameraActivity.kt)
- preview overlay in [`PlateDebugOverlayView.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/ui/PlateDebugOverlayView.kt)

That means we can add assisted ROI without rewriting the whole pipeline.

## Concrete implementation plan

### Phase 1: Add uncertain state and manual fallback

Goal:

- Give the operator a deterministic recovery path when auto-detection fails.

Implementation:

- Add a camera-session state for `AUTO`, `ASSISTED_TARGETING`, and `MANUAL_ENTRY_PENDING`.
- Track "frames since last saved crop" or "time since last confident detection" in [`CameraActivity.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/CameraActivity.kt).
- When the timeout is hit, show a visible action:
  `Tap plate area`
  `Enter manually`

Expected outcome:

- No more dead-end workflow when detection fails completely.

### Phase 2: Add tap-to-target ROI creation

Goal:

- Allow crop creation without detector success.

Implementation:

- Make the preview overlay or preview container accept taps.
- Convert tap position from view space to image/upright bitmap space.
- Create a helper that builds a plate-shaped crop rectangle around the tap.
- Start with 2 or 3 fixed aspect-ratio crop variants around the tap:
  - standard plate box
  - slightly wider box
  - slightly taller box
- Run OCR across those variants, similar to how [`PlateOcrEngine.kt`](/C:/Users/andre/OneDrive/Documents/learning_to_code/model-test/app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt) already tries multiple bitmap variants.

Expected outcome:

- Dirty plates that fail detector recall can still reach OCR.

### Phase 3: Add autofocus / metering to the tap gesture

Goal:

- Improve image sharpness for live capture after operator targeting.

Implementation:

- On tap, trigger CameraX focus and metering for the tapped area.
- Keep this as an enhancement, not the only action.

Expected outcome:

- Better live capture quality, especially on borderline cases.

### Phase 4: Add OCR confidence gates for manual escalation

Goal:

- Avoid pretending the OCR result is trustworthy when it is not.

Implementation:

- Define a low-confidence OCR rule using existing OCR outputs:
  - blank text
  - low confidence
  - low agreement count
  - small score margin
- If the OCR result is below threshold, show manual entry instead of silently using weak text.

Expected outcome:

- Cleaner distinction between machine-read plates and operator-entered plates.

### Phase 5: Instrument and collect failure data

Goal:

- Improve the detector later with real examples.

Implementation:

- Log when uncertain mode is entered.
- Save assisted crops that required manual entry.
- Save metadata:
  - auto-detect failed
  - tap-target used
  - OCR failed or weak
  - final source was manual

Expected outcome:

- Better dataset for later threshold tuning or retraining.

## Minimal version to build first

If we want the smallest useful change, do this:

1. Detect "no saved crop for X seconds / Y frames".
2. Show `Tap plate area` button.
3. Let operator tap preview.
4. Create fixed crop around tap.
5. Run OCR on that crop.
6. If OCR is weak, prompt manual plate entry.

This is the fastest path to a working fallback.

## Important design decision

We should not require the detector to say "uncertain" in a sophisticated ML sense before enabling fallback.

Why:

- Today the detector often fails by returning nothing at all.
- In that case, "uncertain" should include the absence of a confident result, not just low-confidence outputs.

Practical definition for now:

- `uncertain = no confident crop available within a short window`

That is enough to drive the UX.

## Suggested heuristics

Start simple and tune later.

Detection uncertainty trigger:

- no saved crop after about 2 to 3 seconds of active camera viewing, or
- no active track for N consecutive analysis frames, or
- repeated candidate loss after short-lived weak detections

OCR manual-escalation trigger:

- OCR text blank, or
- OCR confidence below threshold, or
- OCR agreement count too low, or
- OCR score margin too small

## Risks

### Risk 1: Too many manual prompts

If the uncertainty trigger is too aggressive, operators will get interrupted too often.

Mitigation:

- make fallback operator-invoked first
- only show prompt after a short delay
- allow dismissing and continuing auto mode

### Risk 2: Bad tap-to-image coordinate mapping

If preview-to-image mapping is wrong, assisted crops will miss the plate.

Mitigation:

- draw the assisted ROI on the overlay before capturing
- test on portrait/rotation cases
- test against PreviewView scaling behavior

### Risk 3: Manual entry introduces data quality issues

Operators can mistype plates.

Mitigation:

- normalize input format
- require confirmation
- preserve crop/evidence with the manual entry

## Best recommendation

Best execution order:

1. Build uncertain-detection UX and manual fallback.
2. Build tap-to-target ROI crop creation.
3. Add autofocus/metering on tap.
4. Add OCR confidence gating.
5. Use collected failures to tune thresholds or retrain detector later.

## Bottom line

The best route is **not** "make tap-to-focus and hope detection starts working."

The best route is:

- accept that dirty plates may bypass detection entirely
- give the operator a way to directly target the plate region
- try OCR on that assisted crop
- escalate cleanly to manual entry when OCR is still uncertain

That gives us a robust operational workflow now, while leaving room for detector improvements later.
