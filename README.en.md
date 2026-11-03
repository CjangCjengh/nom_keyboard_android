<p align="center"><img src="icon.png" alt="Nom Keyboard" width="96" /></p>

# Nom Keyboard for Android

[Tiếng Việt](README.md)

Type Vietnamese with Telex and tap a suggestion to enter Hán Nôm characters. The QWERTY keyboard has Gboard-style light and dark themes and a round launcher icon for Pixel.

## Install and type

1. Install the APK from [GitHub Releases](https://github.com/CjangCjengh/nom_keyboard_android/releases/latest) on Android 7.0 or later. Install updates over the existing app to keep your personal dictionary.
2. Open **Nom Keyboard**. Use the first two entries to enable the keyboard in Android settings and select it as your input method.
3. Type one or more syllables. Tap a candidate to select it or tap the arrow to expand the list.

**Preferences** includes tone placement, space-bar behavior, abbreviated input, text size and the bundled Han-Nom Gothic font. The personal dictionary and learned n-gram frequencies both support TSV import and export.

## Build

Open the project root in Android Studio with JDK 17 and Gradle 8.9. Run `gradle :app:assembleDebug` for a test APK. To sign a release, copy `keystore.properties.template` to `keystore.properties` and supply your signing key details.
