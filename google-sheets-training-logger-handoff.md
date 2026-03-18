# Google Drive + Google Sheets Training Logger Handoff

## Goal

Add a lightweight data collection pipeline to the Android app so that each saved plate crop can be logged for future OCR training.

The system should:
1. keep saving cropped plate images locally as it does now
2. upload selected cropped images to a Google Drive folder
3. append one metadata row to a Google Sheet
4. make later review and correction easy for training-data creation

This is intended for data collection, not production backend design.

---

## Recommended architecture

Use **Google Apps Script as a web app** between the Android app and Google Workspace.

### Flow

1. Android app saves a crop locally.
2. OCR runs and produces predicted text.
3. Android app sends one HTTP POST to an Apps Script web app endpoint.
4. Apps Script:
   - decodes the uploaded image
   - saves it to a specific Google Drive folder
   - gets the Drive file URL and file ID
   - appends one row to a Google Sheet
   - returns JSON success/failure to Android

### Why this approach

This avoids wiring direct Google Drive and Sheets OAuth flows into Android.

Apps Script can:
- be deployed as a web app
- handle POST requests
- create files in Drive
- append rows to Sheets

Official references:
- Apps Script deployments
- ContentService
- DriveApp
- Sheet.appendRow

---

## Google setup to create manually

### 1. Create a Google Drive folder
Create a folder for training crops, for example:
- `ALPR Training Crops`

Save the folder ID.

### 2. Create a Google Sheet
Create a spreadsheet for metadata, for example:
- `ALPR Training Log`

In the first sheet, create this header row:

```text
timestamp,ocr_predicted,drive_file_id,drive_file_url,filename,confidence,plate_length,source,needs_review,corrected_label,notes
```

Save:
- spreadsheet ID
- sheet tab name

### 3. Create an Apps Script project
Create a standalone Apps Script project.

Set constants for:
- spreadsheet ID
- sheet name
- Drive folder ID
- optional shared secret

### 4. Deploy as a web app
Deploy the Apps Script as a web app.

Use a deployment that allows HTTP requests from your app and returns JSON text.

---

## Recommended POST contract from Android to Apps Script

Use JSON over HTTPS.

### Request JSON

```json
{
  "secret": "YOUR_SHARED_SECRET",
  "timestamp": "2026-03-17T20:15:30Z",
  "ocr_predicted": "ABC1234",
  "confidence": 0.91,
  "filename": "plate_20260317_201530.jpg",
  "source": "camera_live",
  "needs_review": true,
  "image_base64": "BASE64_ENCODED_JPEG_BYTES"
}
```

### Notes

- `secret` is optional but strongly recommended.
- `timestamp` should be ISO-8601.
- `confidence` can be omitted if not available yet.
- `needs_review` should default to `true` for now if the goal is training data collection.
- `image_base64` should contain only the encoded bytes, not a `data:` URI prefix.

### Response JSON

Success:
```json
{
  "ok": true,
  "drive_file_id": "abc123",
  "drive_file_url": "https://drive.google.com/...",
  "row_appended": true
}
```

Failure:
```json
{
  "ok": false,
  "error": "missing image_base64"
}
```

---

## Apps Script implementation requirements

### Behavior

The script should:
1. parse POST body as JSON
2. validate required fields
3. validate shared secret if enabled
4. decode base64 image bytes
5. create a Blob from the image
6. save the Blob to the target Drive folder
7. append metadata to the target Google Sheet
8. return JSON using ContentService

### Required fields to validate

At minimum:
- `timestamp`
- `ocr_predicted`
- `filename`
- `image_base64`

### Suggested defaults
- `confidence`: empty string
- `source`: `"unknown"`
- `needs_review`: `true`
- `corrected_label`: empty string
- `notes`: empty string

### Data written to the sheet

Append exactly one row per upload:
1. timestamp
2. ocr_predicted
3. drive_file_id
4. drive_file_url
5. filename
6. confidence
7. plate_length
8. source
9. needs_review
10. corrected_label
11. notes

`plate_length` should be derived from `ocr_predicted.length`.

---

## Apps Script code skeleton

```javascript
const SPREADSHEET_ID = "YOUR_SPREADSHEET_ID";
const SHEET_NAME = "Sheet1";
const DRIVE_FOLDER_ID = "YOUR_DRIVE_FOLDER_ID";
const SHARED_SECRET = "YOUR_SHARED_SECRET";

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: "missing POST body" });
    }

    const payload = JSON.parse(e.postData.contents);

    if (SHARED_SECRET && payload.secret !== SHARED_SECRET) {
      return jsonResponse({ ok: false, error: "unauthorized" });
    }

    const timestamp = payload.timestamp || new Date().toISOString();
    const ocrPredicted = String(payload.ocr_predicted || "").trim();
    const filename = String(payload.filename || "").trim();
    const imageBase64 = String(payload.image_base64 || "").trim();
    const confidence = payload.confidence ?? "";
    const source = String(payload.source || "unknown");
    const needsReview = payload.needs_review ?? true;
    const correctedLabel = String(payload.corrected_label || "");
    const notes = String(payload.notes || "");

    if (!ocrPredicted) {
      return jsonResponse({ ok: false, error: "missing ocr_predicted" });
    }
    if (!filename) {
      return jsonResponse({ ok: false, error: "missing filename" });
    }
    if (!imageBase64) {
      return jsonResponse({ ok: false, error: "missing image_base64" });
    }

    const bytes = Utilities.base64Decode(imageBase64);
    const blob = Utilities.newBlob(bytes, "image/jpeg", filename);

    const folder = DriveApp.getFolderById(DRIVE_FOLDER_ID);
    const file = folder.createFile(blob);

    const fileId = file.getId();
    const fileUrl = file.getUrl();

    const spreadsheet = SpreadsheetApp.openById(SPREADSHEET_ID);
    const sheet = spreadsheet.getSheetByName(SHEET_NAME);
    if (!sheet) {
      return jsonResponse({ ok: false, error: "sheet not found" });
    }

    sheet.appendRow([
      timestamp,
      ocrPredicted,
      fileId,
      fileUrl,
      filename,
      confidence,
      ocrPredicted.length,
      source,
      needsReview,
      correctedLabel,
      notes
    ]);

    return jsonResponse({
      ok: true,
      drive_file_id: fileId,
      drive_file_url: fileUrl,
      row_appended: true
    });
  } catch (err) {
    return jsonResponse({
      ok: false,
      error: String(err)
    });
  }
}
```

---

## Android implementation requirements

### Trigger point in app

Do not change the detector or OCR flow.

Add this upload path **after**:
- the crop has been saved
- OCR has completed
- there is a non-empty OCR prediction, or a review-worthy failed sample

### Suggested client behavior

Create a small class such as:
- `TrainingLogUploader`

Responsibilities:
- read saved crop file
- convert image bytes to base64
- build JSON payload
- POST to Apps Script endpoint
- log success/failure
- run off the main thread

### Recommended Kotlin data model

```kotlin
data class TrainingLogPayload(
    val secret: String,
    val timestamp: String,
    val ocr_predicted: String,
    val confidence: Float? = null,
    val filename: String,
    val source: String = "camera_live",
    val needs_review: Boolean = true,
    val image_base64: String,
    val corrected_label: String? = null,
    val notes: String? = null
)
```

### HTTP implementation notes

Codex should implement this with your existing networking stack if present, otherwise one of:
- `OkHttp`
- `HttpURLConnection`

Requirements:
- `POST`
- `Content-Type: application/json`
- timeout handling
- background thread / coroutine
- do not block camera or OCR pipeline
- best-effort logging only

### Upload policy

For first pass, upload only when:
- crop file exists
- filename exists
- OCR result is non-empty

Optional later:
- also upload low-confidence blank results with `ocr_predicted=""` and `needs_review=true`

---

## App configuration to add

Codex should add configurable constants for:
- Apps Script endpoint URL
- shared secret
- enable/disable training logging
- upload only on Wi-Fi (optional)
- minimum confidence threshold (optional)

These should not be hardcoded inside core OCR logic.

Possible placement:
- `BuildConfig`
- local config object
- `gradle.properties` to `BuildConfig`

---

## Suggested Android integration steps

1. Add endpoint URL and secret config.
2. Add `TrainingLogUploader`.
3. After OCR result is ready, call uploader in background.
4. Read crop bytes from saved file.
5. Base64 encode JPEG bytes.
6. Send JSON payload to Apps Script endpoint.
7. Log result and continue app flow regardless of upload result.

---

## Suggested upload decision logic

### Good default
Upload when all are true:
- crop file exists
- OCR text is non-empty
- logging is enabled

### Better for training later
Upload when either:
- OCR text is non-empty
- OR OCR failed but crop is interesting and marked `needs_review=true`

This gives you both:
- positive OCR examples
- hard negatives / hard review cases

---

## Manual review workflow later

The sheet is meant to support later correction.

Columns to use:
- `ocr_predicted`: model output
- `corrected_label`: human-corrected value
- `needs_review`: whether this row still needs checking
- `notes`: optional comments like `dirty`, `blur`, `night`, `partial`

This makes later training export much easier.

---

## Export-for-training idea later

Later, a separate script can:
1. read rows from the sheet
2. keep rows where `corrected_label` is non-empty
3. download images from Drive
4. write a CSV like:

```text
image_path,label
images/plate_001.jpg,ABC1234
images/plate_002.jpg,XYZ9988
```

This future export script is out of scope for the first implementation.

---

## Security and scope notes

### Minimum security
Use a shared secret in the JSON payload and reject requests with the wrong secret.

### Privacy / access
The Drive folder and sheet should stay in your own Google account or project account.

### Deployment note
If the endpoint is public enough for the app to call it, the secret is the minimum safeguard.

---

## Official references Codex should follow

Apps Script can be deployed and managed as web apps. Apps Script can respond to POST requests with JSON text using ContentService. Apps Script can create files in Drive with `DriveApp.createFile(blob)` or folder-based file creation. Apps Script can append rows to Sheets with `appendRow`.

---

## Acceptance criteria

Implementation is complete when:
1. Android can POST one saved crop and metadata to the Apps Script endpoint.
2. Apps Script saves the image into the configured Drive folder.
3. Apps Script appends exactly one metadata row to the target Sheet.
4. The row includes file ID and file URL.
5. Android logging shows success/failure without interrupting OCR flow.
6. Manual review in Sheets is possible using `corrected_label` and `needs_review`.

---

## One-line summary

Implement a lightweight training logger by posting saved crop images and OCR metadata from Android to an Apps Script web app that stores images in Google Drive and appends metadata rows to Google Sheets.
