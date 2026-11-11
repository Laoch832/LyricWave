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

- Batch each development conversation round into one stable release. Accepted experimental features must have a usable switch connected to the actual runtime in that release.
- Daily new features must have an observable user benefit (for example fewer steps,
  shorter measured waits, smoother playback/UI, or a working recovery flow) and explicit
  end-to-end acceptance criteria. Logs, receipts, contract inventories, tests, internal
  refactors, documentation and an unconnected settings switch are maintenance work,
  not delivery of the daily user-experience feature. Report unfinished work honestly.
- Experimental features need a real, tested runtime path behind their switch, clear
  supported-version limits, cancellation/resource cleanup and fail-open behavior.
  A read-only probe is not a working preloader; enabling an experiment is not proof
  of physical-device compatibility. Stable releases remain numeric x.y.z.

- For AI lyric translation and caching changes, run `./gradlew :core:jvmTest`
  and `python scripts/verify-architecture.py`.
- Cache reuse and update detection must compare the currently selected source TTML (native, online or imported);
  an older translation must not override changed vocals, timings or an existing
  translation in the selected source.

# Cloud builds and releases

- Follow `docs/ci-and-releases.md` for the CI, signing and personal-account release workflow.
- Preserve the existing signing key and pinned certificate. Never regenerate it as a build fix.
- User requires stable releases only: use numeric x.y.z versions without beta/rc/custom suffixes. A signed Actions Artifact alone is not release delivery; complete and verify the GitHub Release publication. Each release must have its own structured release-notes/x.y.z.json, never recycle a fixed historical feature list.
- Bump both app version fields for each new release; do not overwrite an existing release.
- Run Python release-policy tests and workflow lint when changing publishing or signing.

# Project identity

- Public project name: LyricWave; Chinese name: 澜音. Keep user-facing copy and new artifacts consistent.
- The second README section contains upstream acknowledgments; preserve `docs/ACKNOWLEDGMENTS.md`, original copyright notices and license texts.
- Keep `dev.amenhancer.module`, plugin API namespaces, existing storage keys, signing certificate and `AMPP_*` secret names compatible with installed versions.

- For AI activity UI changes, verify actual settings buttons against a local fake API with `AiLyricsActivityUiTest`, including cache hits, displayed counters and page detachment.
- Never infer missing API usage or persist API keys, request/response bodies or lyrics in the activity journal.

# Sharing scope constraint

- Never hook or inject code into WeChat (`com.tencent.mm`), even if the manager retains an old scope selection.
- The user explicitly permits NetEase Cloud Music as the sharing intermediary. Reuse its actual native sharing flow, preserve the original clickable Apple Music URL by default; the user explicitly authorizes an optional Mainland China storefront setting that replaces only the country path segment with cn while retaining song/album IDs, encoded path and query parameters; and never post automatically.
- Song metadata must match the shared track ID (album links use `i`); never borrow the playing song for another URL or overwrite manual edits. Use song title/artist in the native share resource, without fixed Apple Music branding as the title/subtitle.
- NetEase sharing must bootstrap from actual foreground Activities, deduplicate each request and acknowledge only a resumed native panel carrying the original URL. Test real sender UI and receiver delivery; native helper return alone is not proof the panel appeared. Keep diagnostics free of URLs/content and distinguish missing scope from unsupported builds.

# Playback recovery

- Never rewrite playback URLs/credentials or force playback contrary to explicit user intent.
- The user explicitly authorized an opt-in buffered DRM recovery experiment on 2026-11-11: temporarily select the real native next item, then restore the original item/audible position and original play intent; one additional attempt may evict only verified current-song temporary streaming cache. Preserve queue contents/order, offline downloads and DRM credentials. Cancel on user input, bound time/attempts, and do not broaden this exception to other recovery paths.
- Existing non-experimental recovery must not skip tracks or edit the queue.
- For non-experimental recovery, only confirmed recoverable network-source errors in native IDLE may reprepare; automatic recovery is bounded and pause/song-change guards must be tested.
- A spinner or a unit-test pass is not evidence that the user's audio problem is resolved. Keep error reporting free of signed URLs and credentials.
