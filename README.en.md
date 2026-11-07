# LyricWave

LyricWave (澜音) is an Apple Music enhancement project for Android, maintained by [Laoch832](https://github.com/Laoch832). This fork focuses on AI lyric translation, persistent caching, source updates, and a verified build and release process.

[Download](https://github.com/Laoch832/LyricWave/releases) · [CI](https://github.com/Laoch832/LyricWave/actions/workflows/build.yml) · [Issues](https://github.com/Laoch832/LyricWave/issues) · [Documentation](docs/README.md) · [简体中文](README.md)

## Overview

Manage features inside Apple Music → Settings → **LyricWave module settings**. Laoch832 maintains this fork's development, issue tracking, and releases.

AI translations are saved after the player validates them. Later visits reuse the cache when the current source lyrics and translation settings still match. Changed lyrics or timings invalidate the old translation. Tablet playback, custom lyrics, fonts, lyric effects, Liquid Glass, and ZIP plugins remain available.

## Acknowledgments and origins

Thank you to **[Zennmn (Zen)](https://github.com/Zennmn)**, the original author of [AM++](https://github.com/Zennmn/AM-plus-plus), and to its contributors for the lyric processing, host integration, player layouts, and settings foundation. This fork builds on their work and retains the contribution history and copyright notices.

The original author's support link and the credits for other projects are collected in **[Acknowledgments and project origins](docs/ACKNOWLEDGMENTS.md)**. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licensing and source provenance.

## Features

- AI translation of eligible native foreign-language lyrics into Simplified Chinese through your own OpenAI-compatible endpoint.
- Automatic persistent translation caching and source TTML SHA-256 checks, including lyric and timing changes.
- Custom TTML import, editing, online fetching, search, and ZIP backup and restore.
- Custom fonts, bidirectional lyric blur, CJK effects, and a tablet dual-pane player.
- Liquid Glass navigation, mini-player effects, and collapsible phone navigation.
- Regional song information and independently installed ZIP plugins.

### Translation behavior

Enable custom lyrics, automatic completion, and AI translation, then configure your endpoint and model. Existing translations, Chinese lyrics, and lyrics without line or word timing are preserved.

The cache retains the 100 most recently used songs. It is independent of manually imported lyrics and does not store API keys. Endpoint, model, or effective thinking changes invalidate translations; rotating only the API key does not. Updates are checked against the native lyrics currently supplied by Apple Music, without separate server polling. Failed or cancelled requests keep the original lyrics and can retry on the next visit.

## Download and installation

Requires Android 8.0+, a supported Apple Music build, and LSPosed or a compatible framework supporting libxposed API 102. Exact host builds are listed in [host profiles](host-applemusic/src/main/resources/host-profiles/).

1. Download the APK from [this project's Releases](https://github.com/Laoch832/LyricWave/releases).
2. Enable the module and select only Apple Music (`com.apple.android.music`) in its scope.
3. Force stop and reopen Apple Music.
4. Open Apple Music → Settings → **LyricWave module settings**.

This fork uses its own fixed signing key. Back up settings and custom lyrics, then uninstall the upstream module before migrating. Later releases signed with this fork's key support in-place updates.

## Usage

Configure AI translation in the module settings. Import or fetch custom TTML lyrics using the current song's Apple Music ID. Import TTF/OTF fonts and adjust player or glass features in their respective settings. Restart Apple Music where the UI requests it.

See the [TTML guide](docs/apple-music-ttml-format.md), [plugin SDK reference](docs/plugin-development.md), and [documentation index](docs/README.md).

## Cloud builds and releases

Commits and pull requests run source checks, unit tests, lint, and APK builds. Trusted main-branch builds also produce APKs signed with the fixed key. To publish, increase both version fields, then push the matching version tag or manually run CI with `publish` enabled.

Publication verifies the personal **Laoch832** account, source commit, application version, checksum, and certificate. Existing release versions cannot be overwritten by this workflow. See the [build and release guide](docs/ci-and-releases.md) for artifacts, retention, signing, and maintenance.

## Building from source

Use JDK 21, Android SDK Platform 37.0, Build Tools 37.0.0, and the included wrapper.

```bash
chmod +x gradlew
python3 scripts/build-plugin-example.py
./gradlew test :app:lintDebug :app:lintVitalRelease :app:assembleDebug :app:assembleRelease -PpluginFixture="$PWD/build/plugin-example/basic-plugin.zip"
```

On Windows, use `gradlew.bat` and the appropriate fixture path. Without signing configuration, the release output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Use `keystore.properties.example` for local signing; published builds use the configured fixed signing process.

## Contributing, privacy, and licenses

Report problems in [this fork's Issues](https://github.com/Laoch832/LyricWave/issues), including device, Android and Apple Music versions, and reproduction steps. Never include API keys. Changes are developed and verified here before proposing an upstream PR.

The module declares only `INTERNET` permission, imports files through the system picker, and stores settings, lyrics, and fonts in Apple Music's private directory. It has no separate launcher icon or analytics service.

Licensed under [GNU GPL v3.0](LICENSE). Original copyrights and third-party notices remain available in [Acknowledgments](docs/ACKNOWLEDGMENTS.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
