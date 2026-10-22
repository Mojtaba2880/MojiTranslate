# MojiTranslate

Android floating-screen translator focused on English-to-Persian translation.

## How it works

1. Android screen capture starts only after the user grants the system permission.
2. ML Kit OCR first scans with the Latin recognizer, then uses additional Chinese, Devanagari, Japanese, and Korean recognizers during recovery passes when the initial result is sparse. OCR coverage still depends on the scripts supported by these recognizers.
3. Translation mode can be **automatic**, **online only**, or **offline only**. Online mode sends extracted text to Google's public, undocumented Translate web endpoint and requests automatic source detection (`sl=auto`) with Persian as the target (`tl=fa`). Offline mode uses a downloaded ML Kit translation model for the selected source language; its availability and quality depend on ML Kit's supported languages and the model being present.
4. The Persian result is shown in a floating overlay.

## Build

Use JDK 17 and Android SDK 35. GitHub Actions builds a debug APK and uploads it as the `MojiTranslate-debug-apk` artifact.

## Use

1. Install the APK and open MojiTranslate.
2. Grant overlay permission.
3. Choose automatic, online-only, or offline-only translation.
4. For offline translation, select a source language in the model-download section and tap **دانلود مدل انتخاب‌شده** while connected to the internet. The app's Wi-Fi-only download checkbox is enabled by default; disable it if you intentionally want to permit model downloads over mobile data.
5. Start the floating translator and accept Android's screen-capture prompt.
6. Tap the floating button on a screen containing text.

## Important limitations

- Online translation requires internet access and uses Google's public, undocumented Translate web endpoint without an API key. This is not an official supported API, so Google may throttle, block, or change it; availability can vary by network or region.
- Offline translation does not require a network after the necessary model is downloaded, but it depends on ML Kit supporting the selected source language and on that model being installed. Automatic mode can use online translation and then offline fallback according to the app's current logic.
- OCR quality depends on text size, contrast, curvature, layout, supported script, and screen content. Secure/protected screens may block capture. Curved-text correction is attempted on detected text-line crops; it cannot recover text that OCR never detects.
- In online mode, extracted text is transmitted to Google's endpoint. Do not use online translation for confidential text.
- Persian is the fixed target language. Automatic online source detection is not a guarantee that every language or mixed-script line will translate correctly.
- A green GitHub Actions run confirms that unit tests and the configured APK build/validation steps passed for that commit; it does not verify translation quality or runtime behavior on a physical phone. Device testing is still required.
