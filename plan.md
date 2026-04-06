# Branch Coverage Execution Plan

## Baseline (Updated)
- Overall branch coverage: 81.70%
- Covered branches: 777
- Missed branches: 174
- Total branches: 951

## Objective
Continue high-ROI branch improvements and prepare a realistic path to strict 100% (with an explicit include/exclude policy for generated/native-heavy code).

## Completed Since Last Plan
- Added deterministic runtime seam for PlateOcrEngine:
	- OcrRuntime
	- OcrSession
	- OcrOutput
	- OcrTensor
	- OcrExecutor
	- PlateOcrEnvironment and production adapters
- Added PlateOcrEngineTest with constructor, recognize, runInference, and close-path coverage.
- Kept and validated YoloTflitePlateCandidateGenerator seam and tests.
- Verified:
	- testDebugUnitTest passes
	- jacocoTestDebugUnitTestReport passes

## Coverage Impact (What Improved)
- PlateOcrEngine moved from a major blocker to mostly covered.
- YoloTflitePlateCandidateGenerator now has high deterministic coverage.
- Global branch coverage increased from 72.29% to 81.70%.

## Remaining High-Value Blockers
- CameraActivity (still the largest branch sink; many callback/wrapper branches)
- ViolationManager (upload and file edge branches remain)
- RegistryManager (small, likely fast wins)
- DisplayBitmapLoader (small, likely fast wins)
- FrameBitmapUtilsKt (few remaining edge branches)

## Next Session Plan (Priority Order)

### 1) Fast Wins First
- Target DisplayBitmapLoader remaining branches.
- Target RegistryManager remaining branches.
- Goal: fast branch gain with small effort.

### 2) ViolationManager Cleanup
- Add/expand ViolationManager tests for remaining upload/file edge cases:
	- deleteOriginalAssetIfStaged
	- deleteFileQuietly
	- stageFile
	- loadQueue/saveQueue error handling
- Goal: reduce remaining missed branches in a class that already has good coverage.

### 3) FrameBitmapUtilsKt Edge Branches
- Add focused tests for toUprightBitmap success/failure edge paths.
- Goal: clean up low-count misses in utility code.

### 4) CameraActivity Last
- Revisit only after smaller classes are cleaned up.
- Focus only on real branch opportunities, not low-value generated wrappers first.
- Goal: avoid spending high effort on low-ROI Android glue until other wins are exhausted.

### 5) Strict 100% Policy Decision
- Decide before final push:
	- Option A: include all generated/native-heavy classes
	- Option B: exclude selected generated/native-heavy classes from branch gate
- Record the decision in this file before final sprint.

## Codex Execution Rules
- Do not refactor production logic unless a seam is required for a test.
- After each mini-batch of tests, run:
	1. .\\gradlew.bat testDebugUnitTest
	2. .\\gradlew.bat jacocoTestDebugUnitTestReport
- Parse JaCoCo XML after each batch and re-rank blockers by missed branches.
- Continue in updated blocker order instead of the original phase order if rankings change.

## Next Session Checklist
- [ ] Run full tests and regenerate JaCoCo at session start.
- [ ] Record fresh top 10 classes by missed branch count.
- [ ] Finish DisplayBitmapLoader remaining branches.
- [ ] Finish RegistryManager remaining branches.
- [ ] Reduce ViolationManager remaining branch misses.
- [ ] Reduce FrameBitmapUtilsKt remaining branch misses.
- [ ] Reassess CameraActivity branch ROI.
- [ ] Make and document strict-100 include/exclude decision.

## Done Criteria
- Coverage increases each session.
- No test regressions.
- Every completed session updates this file with:
	- coverage snapshot
	- what was completed
	- top blockers remaining
	- exact next-session priorities
