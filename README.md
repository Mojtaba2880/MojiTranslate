# MojiTranslate

Android floating-screen translator focused on English-to-Persian translation.

## How it works

1. Android screen capture is started only after the user grants the system permission.
2. ML Kit Latin OCR extracts visible English text from the screen.
3. The extracted text is sent over the internet to Google's public, undocumented Translate web endpoint and translated from English (`en`) to Persian (`fa`).
4. The Persian result is shown in a floating panel.

## Build

Use JDK 17 and Android SDK 35. GitHub Actions builds a debug APK and uploads it as the `MojiTranslate-debug-apk` artifact.

## Use

1. Install the APK and open MojiTranslate.
2. Grant the overlay permission.
3. Start the floating translator and accept Android's screen-capture prompt.
4. Connect the phone to the internet.
5. Tap the floating button on a screen containing English text.

## Important limitations

- Translation is online; internet access is required each time.
- The app uses Google's public, undocumented Translate web endpoint without an API key. This is not an official supported API, so Google may throttle, block, or change it at any time. Availability can vary by network or region.
- OCR quality depends on text size, contrast, layout, and screen content. Secure/protected screens may block capture.
- Extracted text is transmitted to Google's endpoint for translation. Do not use it for confidential text.
- The app targets English-to-Persian translation, not all world languages.
- A green GitHub Actions run confirms that the APK builds; it does not verify translation quality or runtime behavior on a physical phone. Device testing is still required.
