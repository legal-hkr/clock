# <img src="metadata/en-US/icon.png" width="42"/> Clock

A private, offline clock for Android: alarms, world clock, timer and stopwatch.

**Current version**: in development

Source code and releases: [git.hnet.sh/hkr/clock](https://git.hnet.sh/hkr/clock)

## Overview

Clock is an alarm clock, world clock, timer and stopwatch with home screen widgets. It has no internet permission, doesn’t ask for your location, storage or media, and keeps nothing about you: alarms and settings stay on the device.

## Requirements

- Android 14 (API level 34) or higher

## Building

```bash
git clone https://git.hnet.sh/hkr/clock.git
cd clock
./gradlew assembleDebug
```

Set the Android SDK path in `local.properties` (`sdk.dir=…`) first. Unit tests run with `./gradlew test`.

## Tech stack

- **Language**: Kotlin
- **Min SDK**: Android 14 (API 34), **target SDK**: API 37
- **UI**: Jetpack Compose with Material 3 Expressive

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Acknowledgments

- [Claude](https://claude.ai) by Anthropic – AI assistant that provided valuable support during the development process, helping with code implementation, architecture decisions, and documentation
