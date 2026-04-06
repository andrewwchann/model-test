CameraActivity coverage/refactor handoff

Date: 2026-04-05

What changed in the latest session
- Added a deterministic ONNX/runtime seam for `PlateOcrEngine`
  - `app/src/main/java/com/andre/alprprototype/PlateOcrEngine.kt` now has:
    - `OcrRuntime`
    - `OcrSession`
    - `OcrOutput`
    - `OcrTensor`
    - `OcrExecutor`
    - production adapters (`OrtOcrSession`, `OrtOcrRuntime`, `OrtOcrOutput`, `OrtOcrTensor`, `ExecutorServiceOcrExecutor`)
    - `PlateOcrEnvironment` for public-constructor wiring
  - `PlateOcrEngine` now has an internal injectable constructor for tests
- Added `app/src/test/java/com/andre/alprprototype/PlateOcrEngineTest.kt`
  - covers constructor validation failures
  - covers `recognize()` closed/missing/decode-fail/throwing/success paths
  - covers `runInference()` invalid-output, blank-text, success, and non-`AutoCloseable` output paths
  - covers `close()` idempotency
- Kept the earlier generator seam and tests
  - `app/src/main/java/com/andre/alprprototype/alpr/YoloTflitePlateCandidateGenerator.kt`
  - `app/src/test/java/com/andre/alprprototype/alpr/YoloTflitePlateCandidateGeneratorTest.kt`
- `YoloTflitePlateCandidateGenerator` now has real deterministic coverage instead of being effectively uncovered

Verification
- `.\gradlew.bat testDebugUnitTest --tests com.andre.alprprototype.PlateOcrEngineTest` passes
- `.\gradlew.bat testDebugUnitTest --tests com.andre.alprprototype.alpr.YoloTflitePlateCandidateGeneratorTest` passes
- `.\gradlew.bat jacocoTestDebugUnitTestReport` passes

Current coverage highlights
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype/index.html`
  - package `com.andre.alprprototype`: about 83% instruction / 75% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype/CameraActivity.html`
  - `CameraActivity`: about 96% instruction / 73% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype/ViolationManager.html`
  - `ViolationManager`: about 91% instruction / 85% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype/PlateOcrEngine.html`
  - `PlateOcrEngine`: about 88% instruction / 94% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype.alpr/index.html`
  - package `com.andre.alprprototype.alpr`: about 88% instruction / 83% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype.alpr/YoloTflitePlateCandidateGenerator.html`
  - `YoloTflitePlateCandidateGenerator`: about 98% instruction / 90% branch
- `app/build/reports/jacoco/jacocoTestDebugUnitTestReport/html/com.andre.alprprototype.alpr/FrameBitmapUtilsKt.html`
  - `FrameBitmapUtilsKt`: about 84% instruction / 81% branch

Top remaining blockers from the latest report
1. `CameraActivity`
   - still 47 missed branches
   - most remaining misses are Android callback/wrapper glue

2. `ViolationManager`
   - still 8 missed branches
   - remaining gaps are mostly API/upload edge paths

3. `RegistryManager`
   - still 4 missed branches
   - likely easy deterministic gains remain

4. `DisplayBitmapLoader`
   - still 4 missed branches
   - isolated and probably cheap to finish

5. `FrameBitmapUtilsKt`
   - still 3 missed branches
   - remaining misses are in `toUprightBitmap()` failure/success edge cases

Important notes
- `PlateOcrEngine` is no longer the main branch blocker. The core class is mostly covered now.
- The remaining misses around OCR are mostly non-branch helper/wrapper classes introduced by the seam:
  - `PlateOcrEnvironment`
  - `OrtOcrSession`
  - `OrtOcrRuntime`
  - `OrtOcrOutput`
  - `OrtOcrTensor`
  - `ExecutorServiceOcrExecutor`
- `PlateOcrEngine` still shows one missed branch in JaCoCo inside `runInference()`, even after explicitly hitting the non-`AutoCloseable` path. That likely needs a closer look at the bytecode/JaCoCo accounting before more refactoring.
- `YoloTflitePlateCandidateGenerator` is in much better shape; the remaining misses are now mostly wrapper/default-loader coverage, not the core decision flow.

Best next targets
1. Push `DisplayBitmapLoader`
   - small surface area
   - likely quick branch wins in the main package

2. Revisit `RegistryManager`
   - only 4 missed branches
   - probably better ROI than squeezing more Kotlin-generated `CameraActivity` wrappers first

3. Revisit `ViolationManager`
   - 8 missed branches left
   - still meaningful branch gain available

4. Only then return to `CameraActivity`
   - the remaining gaps are mostly wrapper/callback classes and Android glue, so effort is higher per branch

5. If strict literal 100% is still the goal
   - expect more time to go into generated wrapper classes and production adapter classes, not just business logic
