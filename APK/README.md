# Edge ALPR System: Testing Build

This directory contains the latest Android Application Package (APK) for the Edge ALPR parking enforcement system. This build is provided specifically for client and stakeholder testing purposes.

## Included Files
* `EdgeALPR_TestBuild_v1.0.apk`: The installable Android application file.
* `README.md`: This documentation file.

## Prerequisites for Testing
Before installing the application, please ensure your testing device meets the following requirements:
* **Operating System:** Android 10.0 or newer.
* **Hardware:** A functional rear facing camera is strictly required for the Optical Character Recognition pipeline.
* **Storage:** At least 500 MB of free internal storage to accommodate the local database registry and queued photographic evidence.

## Installation Instructions
Because this application is not distributed through the Google Play Store, you will need to manually install (sideload) the APK onto your device.

1. **Download the APK:** Transfer or download the `EdgeALPR_TestBuild_v1.0.apk` file directly onto your Android device.
2. **Enable Unknown Sources:** * Navigate to your device **Settings**.
   * Go to **Apps** or **Security** (depending on your specific device manufacturer).
   * Find the option for **Install Unknown Apps** and grant permission to the app you are using to open the APK (such as your File Manager or Web Browser).
3. **Install the Application:** Locate the downloaded APK in your device's file manager and tap it to begin the installation. 
4. **Grant Permissions:** Upon opening the application for the first time, you will be prompted to grant permissions. You must allow **Camera** access for the OCR scanner to function and **Network** access for the end of shift synchronization.

## Key Testing Workflows
To fully evaluate the system, we recommend testing the following core functionalities:
* **Offline Plate Recognition:** Turn on Airplane Mode to disconnect from all cellular and Wi-Fi networks. Open the scanner and point it at a license plate to verify the on device OCR successfully reads and cross references the plate against the local registry.
* **Evidence Collection:** Scan an unauthorized plate to trigger a violation. Verify that the app interrupts the scanning loop to prompt for a wide angle context photograph.
* **Batch Synchronization:** Turn your network connection back on. Trigger the end of shift sync button to verify the queued JSON evidence packages successfully upload to the AWS cloud environment.
