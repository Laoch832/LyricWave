import { test } from "node:test";
import assert from "node:assert/strict";
import { getReleases, parseReleases } from "../src/lib/releases";
const release = (id: number, date: string, draft = false) => ({
  id,
  name: `v${id}`,
  tag_name: `v${id}`,
  published_at: date,
  html_url: "https://github.com/Laoch832/LyricWave/releases/tag/v1",
  body: null,
  draft,
  prerelease: false,
  assets: [
    {
      id,
      name: "release.apk",
      size: 1024,
      browser_download_url:
        "https://github.com/Laoch832/LyricWave/releases/download/v1/release.apk",
    },
  ],
});
test("orders releases by publication time and omits drafts", () => {
  const r = parseReleases([
    release(1, "2026-10-01T00:00:00Z"),
    release(3, "2026-11-01T00:00:00Z", true),
    release(2, "2026-10-02T00:00:00Z"),
  ]);
  assert.ok(r.ok);
  assert.deepEqual(
    r.releases.map((r) => r.id),
    [2, 1],
  );
});
test("empty or malformed API response has an explicit fallback", () => {
  assert.deepEqual(parseReleases([]), { ok: false, reason: "empty" });
  assert.deepEqual(parseReleases({ message: "rate limit" }), {
    ok: false,
    reason: "invalid",
  });
});
test("never serves an untrusted asset URL", () => {
  const r = release(1, "2026-10-01T00:00:00Z");
  r.assets[0].browser_download_url = "https://example.com/fake.apk";
  assert.deepEqual(parseReleases([r]), { ok: false, reason: "invalid" });
});

// Exercise real fetch failure boundaries without network or credentials.
test("rate limits and network failures produce a fallback", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response("", { status: 429 });
    assert.deepEqual(await getReleases(), { ok: false, reason: "rate-limit" });
    globalThis.fetch = async () => {
      throw new TypeError("offline");
    };
    assert.deepEqual(await getReleases(), { ok: false, reason: "network" });
  } finally {
    globalThis.fetch = original;
  }
});

test("malformed, credential-bearing and non-download URLs are rejected without throwing", () => {
  for (const url of [
    "not-a-url",
    "https://user:pass@github.com/Laoch832/LyricWave/releases/download/v1/x.apk",
    "https://github.com:8443/Laoch832/LyricWave/releases/download/v1/x.apk",
    "https://github.com/Laoch832/LyricWave/issues/1",
  ]) {
    const r = release(1, "2026-10-01T00:00:00Z");
    r.assets[0].browser_download_url = url;
    assert.deepEqual(parseReleases([r]), { ok: false, reason: "invalid" });
  }
});
test("invalid JSON and rejected HTTP requests preserve the official fallback", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response("bad json", { status: 200 });
    assert.deepEqual(await getReleases(), { ok: false, reason: "invalid" });
    globalThis.fetch = async () => new Response("", { status: 503 });
    assert.deepEqual(await getReleases(), { ok: false, reason: "network" });
  } finally {
    globalThis.fetch = original;
  }
});
