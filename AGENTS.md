# Development repository and publishing identity

- Continue development in `Laoch832/AM-plus-plus-custom`:
  https://github.com/Laoch832/AM-plus-plus-custom
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
- Cache reuse and update detection must compare the current native source TTML;
  an older translation must not override changed vocals, timings or a native
  translation that has become available.
