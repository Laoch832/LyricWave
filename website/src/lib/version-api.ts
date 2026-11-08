import { getReleases, releasesUrl, type ReleasesResult } from "./releases";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, If-Range",
  "Access-Control-Expose-Headers":
    "Content-Length, Content-Range, Accept-Ranges, Content-Disposition, ETag",
};
export function apiError(status: number, code: string) {
  return Response.json(
    { schemaVersion: 1, error: { code }, releasesUrl },
    { status, headers: { ...cors, "Cache-Control": "no-store" } },
  );
}
export function options() {
  return new Response(null, { status: 204, headers: cors });
}

export async function versionResponse(
  request: Request,
  load: () => Promise<ReleasesResult> = getReleases,
) {
  const params = new URL(request.url).searchParams;
  const channel = params.get("channel") ?? "latest";
  if (
    !["latest", "stable", "prerelease"].includes(channel) ||
    [...params.keys()].some((k) => k !== "channel") ||
    params.getAll("channel").length > 1
  )
    return apiError(400, "INVALID_QUERY");
  const result = await load();
  if (!result.ok)
    return apiError(
      result.reason === "empty" ? 404 : 503,
      result.reason === "empty" ? "NO_RELEASE" : "UPSTREAM_UNAVAILABLE",
    );
  const release = result.releases.find(
    (r) =>
      channel === "latest" ||
      (channel === "prerelease" ? r.prerelease : !r.prerelease),
  );
  if (!release) return apiError(404, "NO_RELEASE_IN_CHANNEL");
  return Response.json(
    {
      schemaVersion: 1,
      repository: "Laoch832/LyricWave",
      channel,
      release: {
        id: release.id,
        tag: release.tag_name,
        name: release.name ?? release.tag_name,
        publishedAt: release.published_at,
        prerelease: release.prerelease,
        notes: release.body ?? "",
        htmlUrl: release.html_url,
        versionCode: null,
        assets: release.assets.map((a) => ({
          id: a.id,
          name: a.name,
          size: a.size,
          kind: a.name.toLowerCase().endsWith(".apk") ? "apk" : "other",
          downloadUrl: `/api/v1/download/${a.id}`,
          upstreamUrl: a.browser_download_url,
        })),
      },
    },
    {
      headers: {
        ...cors,
        "Cache-Control": "public, max-age=60, s-maxage=300",
        "X-Content-Type-Options": "nosniff",
      },
    },
  );
}

export function allowedDownloadUrl(value: string, initial = false): boolean {
  try {
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.port ||
      url.hash
    )
      return false;
    if (initial)
      return (
        url.origin === "https://github.com" &&
        url.pathname.startsWith("/Laoch832/LyricWave/releases/download/") &&
        !url.search
      );
    return (
      url.hostname === "release-assets.githubusercontent.com" ||
      (url.hostname === "github.com" &&
        url.pathname.startsWith("/Laoch832/LyricWave/releases/download/"))
    );
  } catch {
    return false;
  }
}

export async function downloadResponse(
  request: Request,
  assetId: string,
  load: () => Promise<ReleasesResult> = getReleases,
  fetcher: typeof fetch = fetch,
  expectedName?: string,
) {
  if (
    !/^[1-9]\d{0,15}$/.test(assetId) ||
    !Number.isSafeInteger(Number(assetId)) ||
    new URL(request.url).search
  )
    return apiError(400, "INVALID_ASSET_ID");
  const result = await load();
  if (!result.ok) return apiError(503, "UPSTREAM_UNAVAILABLE");
  const asset = result.releases
    .flatMap((r) => r.assets)
    .find((a) => String(a.id) === assetId);
  if (!asset || (expectedName !== undefined && asset.name !== expectedName)) return apiError(404, "ASSET_NOT_FOUND");
  const range = request.headers.get("range");
  if (range && !/^bytes=(\d+-\d*|-\d+)$/.test(range))
    return apiError(400, "INVALID_RANGE");
  const headers: Record<string, string> = {
    Accept: "application/octet-stream",
    "Accept-Encoding": "identity",
  };
  if (range) headers.Range = range;
  if (range && request.headers.has("if-range"))
    headers["If-Range"] = request.headers.get("if-range")!;
  try {
    let url = asset.browser_download_url;
    if (!allowedDownloadUrl(url, true)) return apiError(502, "UNSAFE_UPSTREAM");
    const signal = AbortSignal.any([
      request.signal,
      AbortSignal.timeout(55000),
    ]);
    for (let hop = 0; hop < 5; hop++) {
      const upstream = await fetcher(url, {
        method: request.method === "HEAD" ? "HEAD" : "GET",
        headers,
        redirect: "manual",
        cache: "no-store",
        signal,
      });
      if ([301, 302, 303, 307, 308].includes(upstream.status)) {
        const location = upstream.headers.get("location");
        await upstream.body?.cancel();
        if (!location) return apiError(502, "INVALID_REDIRECT");
        url = new URL(location, url).href;
        if (!allowedDownloadUrl(url)) return apiError(502, "UNSAFE_REDIRECT");
        continue;
      }
      if (![200, 206, 416].includes(upstream.status)) {
        await upstream.body?.cancel();
        return apiError(502, "DOWNLOAD_UNAVAILABLE");
      }
      // Asset IDs identify public release files. Cache only complete, length-verified
      // GET responses; never let a partial or failed transfer become a full object.
      const cacheable =
        request.method === "GET" &&
        !range &&
        upstream.status === 200 &&
        asset.size > 0 &&
        upstream.headers.get("content-length") === String(asset.size) &&
        !upstream.headers.has("content-range") &&
        !upstream.headers.has("content-encoding");
      const fallbackName = asset.name.replace(/[^\x20-\x7e]|["\\/]/g, "_").slice(0, 180) || "download.bin";
      const contentType = /\.apk$/i.test(asset.name) ? "application/vnd.android.package-archive"
        : /\.json$/i.test(asset.name) ? "application/json" : /\.txt$/i.test(asset.name) ? "text/plain; charset=utf-8" : "application/octet-stream";
      const output = new Headers({
        ...cors,
        "Cache-Control": cacheable
          ? "public, max-age=0, s-maxage=86400, no-transform"
          : "no-store, no-transform",
        // Leave shared file caching to the outer CDN, not the function platform.
        "Vercel-CDN-Cache-Control": "no-store",
        Vary: "Range, If-Range",
        "X-Content-Type-Options": "nosniff",
        "Content-Type": contentType,
        "Content-Disposition": `attachment; filename="${fallbackName}"; filename*=UTF-8''${encodeURIComponent(asset.name).replace(/['()*]/g, (c) => `%${c.charCodeAt(0).toString(16)}`)}`,
      });
      for (const name of [
        "content-length",
        "content-range",
        "accept-ranges",
        "etag",
        "last-modified",
      ]) {
        const value = upstream.headers.get(name);
        if (value) output.set(name, value);
      }
      if (request.method === "HEAD") await upstream.body?.cancel();
      return new Response(request.method === "HEAD" ? null : upstream.body, {
        status: upstream.status,
        headers: output,
      });
    }
    return apiError(502, "TOO_MANY_REDIRECTS");
  } catch {
    return apiError(502, "DOWNLOAD_UNAVAILABLE");
  }
}
