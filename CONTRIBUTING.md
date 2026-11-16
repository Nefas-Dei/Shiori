Looking to report an issue/bug or make a feature request? Please refer to the [README file](https://github.com/Nefas-Dei/Shiori#readme).

---

Thanks for your interest in contributing to Shiori!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/Nefas-Dei/Shiori/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Getting help

- Join [the Discord server](https://discord.gg/RycBkEgrPE) for online help and to ask questions while developing.

## A note on upstream contributions

Shiori is a fork of [Tsundoku](https://github.com/tsundoku-otaku/tsundoku), a fork of [Mihon](https://github.com/mihonapp/mihon).
General reader improvements that don't depend on Shiori-specific code (for example UI tweaks or reader features) are welcome as pull requests to Tsundoku too — base them on Tsundoku's `main` branch so they stay free of Shiori-specific changes.

# Translations

Translations happen upstream via Weblate — see [Tsundoku's docs](https://tsundoku-otaku.github.io/docs/contribute#translation) for more details.


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/Nefas-Dei/Shiori/blob/main/LICENSE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the app update checker
- To avoid installation conflicts:
    - Change the `applicationId` in `app/build.gradle.kts`
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you use Firebase analytics, replace `app/src/standard/google-services.json` with your own
