const SPREADSHEET_ID = "163QLdxHiINxdW_5gPpIJFrUTlpRzw0fCVOomcFmHRNI";
const SHEET_NAME = "Sheet1";
const DRIVE_FOLDER_ID = "1i2WL83FtNBESeExzcCvHBKm-WumL1Ura";
const SHARED_SECRET = "secretohverysecrtapp";

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

    const timestamp = String(payload.timestamp || new Date().toISOString()).trim();
    const ocrPredicted = String(payload.ocr_predicted || "").trim();
    const filename = String(payload.filename || "").trim();
    const imageBase64 = String(payload.image_base64 || "").trim();
    const confidence = payload.confidence ?? "";
    const source = String(payload.source || "unknown").trim();
    const needsReview = payload.needs_review ?? true;
    const correctedLabel = String(payload.corrected_label || "").trim();
    const notes = String(payload.notes || "").trim();

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
