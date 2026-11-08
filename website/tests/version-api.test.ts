import { test } from "node:test";
import assert from "node:assert/strict";
import {
  versionResponse,
  downloadResponse,
  allowedDownloadUrl,
} from "../src/lib/version-api";
import type { ReleasesResult } from "../src/lib/releases";
const load = async (): Promise<ReleasesResult> => ({
  ok: true,
  releases: [
    {
      id: 1,
      name: "Beta",
      tag_name: "v1-beta",
      published_at: "2026-11-01T00:00:00Z",
      html_url: "https://github.com/Laoch832/LyricWave/releases/tag/v1-beta",
      body: "Notes",
      draft: false,
      prerelease: true,
      assets: [
        {
          id: 12,
          name: "澜音.apk",
          size: 3,
          browser_download_url:
            "https://github.com/Laoch832/LyricWave/releases/download/v1-beta/app.apk",
        },
      ],
    },
  ],
});
test("version channel, schema and honest versionCode", async () => {
  const r = await versionResponse(
    new Request("https://example.com/api/v1/version"),
    load,
  );
  const json = await r.json();
  assert.equal(r.status, 200);
  assert.equal(json.release.versionCode, null);
  assert.equal(json.release.assets[0].downloadUrl, "/api/v1/download/12");
  assert.equal(
    (
      await versionResponse(
        new Request("https://example.com/api/v1/version?channel=stable"),
        load,
      )
    ).status,
    404,
  );
  assert.equal(
    (
      await versionResponse(
        new Request("https://example.com/api/v1/version?url=x"),
        load,
      )
    ).status,
    400,
  );
  assert.equal(
    (
      await versionResponse(
        new Request("https://example.com/api/v1/version"),
        async () => ({ ok: false, reason: "rate-limit" }),
      )
    ).status,
    503,
  );
});
test("proxy rejects arbitrary targets, unknown assets and multiple ranges before fetching", async () => {
  const never = (async () => {
    throw Error("must not fetch");
  }) as typeof fetch;
  for (const [id, url, status] of [
    ["12", "?url=https://evil.test", 400],
    ["99", "", 404],
    ["0", "", 400],
  ] as const)
    assert.equal(
      (
        await downloadResponse(
          new Request(`https://example.com/d/${id}${url}`),
          id,
          load,
          never,
        )
      ).status,
      status,
    );
  assert.equal(
    (
      await downloadResponse(
        new Request("https://example.com/d/12", {
          headers: { range: "bytes=0-1,3-4" },
        }),
        "12",
        load,
        never,
      )
    ).status,
    400,
  );
  assert.equal(
    allowedDownloadUrl(
      "https://release-assets.githubusercontent.com.evil.test/x",
    ),
    false,
  );
  assert.equal(allowedDownloadUrl("http://127.0.0.1/x"), false);
});
test("proxy validates redirects and streams range bytes without credentials", async () => {
  let calls = 0;
  const mock = (async (_url: string | URL | Request, init?: RequestInit) => {
    calls++;
    assert.equal(new Headers(init?.headers).has("authorization"), false);
    assert.equal(new Headers(init?.headers).get("range"), "bytes=0-2");
    return calls === 1
      ? new Response(null, {
          status: 302,
          headers: {
            location:
              "https://release-assets.githubusercontent.com/file?signature=test",
          },
        })
      : new Response("apk", {
          status: 206,
          headers: { "content-range": "bytes 0-2/3", "content-length": "3" },
        });
  }) as typeof fetch;
  const r = await downloadResponse(
    new Request("https://example.com/d/12", {
      headers: { range: "bytes=0-2" },
    }),
    "12",
    load,
    mock,
  );
  assert.equal(r.status, 206);
  assert.equal(await r.text(), "apk");
  assert.equal(r.headers.get("content-range"), "bytes 0-2/3");
  assert.equal(r.headers.get("cache-control"), "no-store, no-transform");
  const unsafe = (async () =>
    new Response(null, {
      status: 302,
      headers: { location: "http://127.0.0.1/secret" },
    })) as typeof fetch;
  assert.equal(
    (
      await downloadResponse(
        new Request("https://example.com/d/12"),
        "12",
        load,
        unsafe,
      )
    ).status,
    502,
  );
});
test("HEAD has no body, upstream failures are explicit", async () => {
  const mock = (async () =>
    new Response(null, { headers: { "content-length": "3" } })) as typeof fetch;
  const r = await downloadResponse(
    new Request("https://example.com/d/12", { method: "HEAD" }),
    "12",
    load,
    mock,
  );
  assert.equal(await r.text(), "");
  assert.equal(r.headers.get("content-length"), "3");
  const failed = (async () => {
    throw Error("timeout");
  }) as typeof fetch;
  assert.equal(
    (
      await downloadResponse(
        new Request("https://example.com/d/12"),
        "12",
        load,
        failed,
      )
    ).status,
    502,
  );
});
