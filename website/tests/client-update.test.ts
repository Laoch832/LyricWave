import { test } from "node:test";
import assert from "node:assert/strict";
import { clientUpdateResponse } from "../src/lib/client-update-api";
import type { ReleasesResult } from "../src/lib/releases";
const metadata = { versionName: "1.7.0", versionCode: 132, applicationId: "dev.amenhancer.module",
 certificateSha256: "4ec2f441139585083e476b596a503db7e6c459ec1ca1d9bcf59d815b6981da53", commit: "a".repeat(40), apk: "LyricWave-1.7.0.apk", apkSha256: "b".repeat(64) };
const config = { ...metadata, schemaVersion: 1, summary: "Actual release summary", changes: [{ type: "fix", title: "Fixed update flow", description: "No downgrade button" }], notes: ["Restart Apple Music"] };
const assets = ["build-metadata.json", "client-update.json", metadata.apk].map((name, i) => ({ id: i + 1, name, size: 1024, browser_download_url: `https://github.com/Laoch832/LyricWave/releases/download/v1.7.0/${name}` }));
const load = async (): Promise<ReleasesResult> => ({ ok: true, releases: [{ id: 1, name: "Stable", tag_name: "v1.7.0", published_at: "2026-11-08T00:00:00Z", html_url: "https://github.com/Laoch832/LyricWave/releases/tag/v1.7.0", body: "No historic boilerplate", draft: false, prerelease: false, assets }] });
const fetcher = (async (url: string | URL | Request) => Response.json(String(url).endsWith("client-update.json") ? config : metadata)) as typeof fetch;
test("client config provides authoritative version and typed changes", async () => {
 const response = await clientUpdateResponse(new Request("https://example.test/api/v1/client-update"), load, fetcher);
 assert.equal(response.status, 200); const data = await response.json(); assert.equal(data.channel, "stable"); assert.equal(data.release.build.versionCode, 132);
 assert.equal(data.release.summary, config.summary); assert.equal(data.release.changes[0].title, "Fixed update flow"); assert.equal(data.release.notes, ""); assert.equal(data.release.assets.length, 1);
});
test("prereleases and inconsistent metadata never establish an upgrade", async () => {
 const beta = async (): Promise<ReleasesResult> => { const data = await load(); if (data.ok) data.releases[0].prerelease = true; return data; };
 assert.equal((await clientUpdateResponse(new Request("https://x/api/v1/client-update"), beta, fetcher)).status, 404);
 const bad = (async (url: string | URL | Request) => Response.json(String(url).endsWith("client-update.json") ? { ...config, versionCode: 133 } : metadata)) as typeof fetch;
 assert.equal((await clientUpdateResponse(new Request("https://x/api/v1/client-update"), load, bad)).status, 502);
});
test("client config rejects arbitrary queries and oversized bodies", async () => {
 assert.equal((await clientUpdateResponse(new Request("https://x/api/v1/client-update?url=x"), load, fetcher)).status, 400);
 const large = (async () => new Response("x".repeat(65537))) as typeof fetch;
 assert.equal((await clientUpdateResponse(new Request("https://x/api/v1/client-update"), load, large)).status, 502);
});
