import { z } from "zod";
import { getReleases, type ReleasesResult, type Release } from "./releases";
import { allowedDownloadUrl, apiError, downloadResponse } from "./version-api";

const repository = "Laoch832/LyricWave";
const certificate = "4ec2f441139585083e476b596a503db7e6c459ec1ca1d9bcf59d815b6981da53";
const change = z.object({ type: z.enum(["fix", "feature", "improvement"]), title: z.string().trim().min(1).max(100), description: z.string().trim().min(1).max(1000) });
const build = z.object({
  versionName: z.string().regex(/^\d+\.\d+\.\d+$/), versionCode: z.number().int().positive().max(2147483647),
  applicationId: z.literal("dev.amenhancer.module"), certificateSha256: z.literal(certificate),
  commit: z.string().regex(/^[0-9a-f]{40}$/), apk: z.string().regex(/^LyricWave-\d+\.\d+\.\d+\.apk$/),
  apkSha256: z.string().regex(/^[0-9a-f]{64}$/),
});
const config = build.extend({ schemaVersion: z.literal(1), summary: z.string().trim().min(1).max(500),
  changes: z.array(change).min(1).max(30), notes: z.array(z.string().max(1000)).max(20) });

async function boundedJson(request: Request, release: Release, name: string, fetcher: typeof fetch) {
  const asset = release.assets.filter(a => a.name === name);
  if (asset.length !== 1 || asset[0].size > 65536 || asset[0].size <= 0 || !allowedDownloadUrl(asset[0].browser_download_url, true)) throw Error("metadata");
  // Reuse the exact redirect policy and credential-free asset transport; never fetch an arbitrary URL.
  const response = await downloadResponse(new Request(new URL(`/api/v1/download/${asset[0].id}`, request.url), { signal: request.signal }),
    String(asset[0].id), async () => ({ ok: true, releases: [release] }), fetcher);
  if (response.status !== 200 || !response.body) throw Error("metadata");
  const reader = response.body.getReader(); const chunks: Uint8Array[] = []; let bytes = 0;
  try {
    while (true) {
      const item = await reader.read(); if (item.done) break;
      bytes += item.value.byteLength;
      if (bytes > 65536) throw Error("size");
      chunks.push(item.value);
    }
  } finally { await reader.cancel(); }
  const text = Buffer.concat(chunks).toString("utf8"); return JSON.parse(text);
}

/** Client-only stable channel, authoritative integer version and typed, per-release changes. */
export async function clientUpdateResponse(request: Request,
  load: () => Promise<ReleasesResult> = getReleases, fetcher: typeof fetch = fetch) {
  if (new URL(request.url).search) return apiError(400, "INVALID_QUERY");
  const result = await load();
  if (!result.ok) return apiError(result.reason === "empty" ? 404 : 503, result.reason === "empty" ? "NO_STABLE_RELEASE" : "UPSTREAM_UNAVAILABLE");
  const release = result.releases.find(r => !r.draft && !r.prerelease);
  if (!release) return apiError(404, "NO_STABLE_RELEASE");
  try {
    const metadata = build.parse(await boundedJson(request, release, "build-metadata.json", fetcher));
    const content = config.parse(await boundedJson(request, release, "client-update.json", fetcher));
    if (release.tag_name !== `v${metadata.versionName}` || Object.keys(metadata).some(k => metadata[k as keyof typeof metadata] !== content[k as keyof typeof content]))
      return apiError(502, "INVALID_UPDATE_CONFIG");
    const apk = release.assets.filter(a => a.name === metadata.apk);
    if (apk.length !== 1 || apk[0].size <= 0 || apk[0].size > 512 * 1024 * 1024) return apiError(502, "INVALID_UPDATE_CONFIG");
    return Response.json({ schemaVersion: 1, clientSchemaVersion: 1, repository, channel: "stable",
      release: { tag: release.tag_name, name: release.name ?? release.tag_name, publishedAt: release.published_at,
        prerelease: false, htmlUrl: release.html_url, notes: "", summary: content.summary, changes: content.changes,
        notices: content.notes, build: metadata,
        assets: [{ id: apk[0].id, name: apk[0].name, size: apk[0].size, kind: "apk",
          downloadUrl: `/api/v1/download/${apk[0].id}`, upstreamUrl: apk[0].browser_download_url }] } },
      { headers: { "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=0, s-maxage=60",
        "X-Content-Type-Options": "nosniff" } });
  } catch { return apiError(502, "INVALID_UPDATE_CONFIG"); }
}
