Place a TensorFlow Lite license-plate detector model at:

license_plate_detector.tflite

Expected behavior:
- The app will automatically prefer this detector over the heuristic fallback.
- The detector parser is designed for common YOLO-style TFLite outputs.
- If the asset is missing or incompatible, the app falls back to the existing heuristic detector.
