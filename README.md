# MojiTranslate

An Android floating translator prototype that captures the visible screen with explicit Android screen-capture consent, extracts text with ML Kit OCR, identifies its language, and translates to Persian using on-device ML Kit translation models.

## Build

Use JDK 17 and Android SDK 35. GitHub Actions builds a debug APK and uploads it as the `MojiTranslate-debug-apk` artifact.

## Use

1. Install the APK and open MojiTranslate.
2. Grant the overlay permission.
3. Start the floating translator and accept Android's screen-capture prompt.
4. Tap the floating button on a screen containing foreign text.
5. Wait for the required translation model to download on first use.

## Limitations

- Translation uses free on-device models; quality is not guaranteed to match Google Translate.
- Model downloads require a working network connection. The app does not use a paid translation API or require an API key.
- Protected/secure screens may block capture. Screen capture requires explicit Android consent.
- This is an early prototype and must be built and tested on a real device before being treated as a release.
