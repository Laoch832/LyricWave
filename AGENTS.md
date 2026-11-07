# Development repository and publishing identity

- Continue development in `Laoch832/LyricWave`:
  https://github.com/Laoch832/LyricWave
- Use this fork as `origin` and `Zennmn/AM-plus-plus` as `upstream`.
  Push ongoing changes to the fork. Keep upstream history so changes can later
  be proposed in a pull request.
- The user requires GitHub writes and future pull requests to use their
  personal account, `Laoch832` (GitHub user ID `173591322`). Verify the
  authenticated account before publishing. Do not publish through a bot account.
- Use the repository-local Git identity `Laoch832` with the privacy email
  `173591322+Laoch832@users.noreply.github.com`. Do not change global Git identity.
- Do not open an upstream pull request as part of routine development. The user
  wants to accumulate changes in this fork before proposing them upstream.

# Verification

- For AI lyric translation and caching changes, run `./gradlew :core:jvmTest`
  and `python scripts/verify-architecture.py`.
- Cache reuse and update detection must compare the currently selected source TTML (native, online or imported);
  an older translation must not override changed vocals, timings or an existing
  translation in the selected source.

# Cloud builds and releases

- Follow `docs/ci-and-releases.md` for the CI, signing and personal-account release workflow.
- Preserve the existing signing key and pinned certificate. Never regenerate it as a build fix.
- Bump both app version fields for each new release; do not overwrite an existing release.
- Run Python release-policy tests and workflow lint when changing publishing or signing.

# Project identity

- Public project name: LyricWave; Chinese name: 澜音. Keep user-facing copy and new artifacts consistent.
- The second README section contains upstream acknowledgments; preserve `docs/ACKNOWLEDGMENTS.md`, original copyright notices and license texts.
- Keep `dev.amenhancer.module`, plugin API namespaces, existing storage keys, signing certificate and `AMPP_*` secret names compatible with installed versions.

- For AI activity UI changes, verify actual settings buttons against a local fake API with `AiLyricsActivityUiTest`, including cache hits, displayed counters and page detachment.
- Never infer missing API usage or persist API keys, request/response bodies or lyrics in the activity journal.
