import fs from "node:fs/promises";
import { unified } from "unified";
import remarkParse from "remark-parse";
import { visit } from "unist-util-visit";
const manifest = JSON.parse(
  await fs.readFile("src/lib/generated/content-map.json", "utf8"),
);
const urls = new Map();
function add(value, source) {
  try {
    const url = new URL(value);
    if (!["https:", "http:"].includes(url.protocol)) return;
    url.hash = "";
    const key = url.href;
    if (!urls.has(key)) urls.set(key, new Set());
    urls.get(key).add(source);
  } catch {
    return;
  }
}
for (const entry of manifest) {
  const stem = "content/docs/" + entry.route.slice("/docs/".length);
  let raw;
  try {
    raw = await fs.readFile(stem + ".md", "utf8");
  } catch {
    raw = await fs.readFile(stem + ".mdx", "utf8");
  }
  visit(unified().use(remarkParse).parse(raw), (node) => {
    if ("url" in node) add(node.url, entry.route);
  });
  for (const match of raw.matchAll(/(?:href|src)="(https?:[^"\s]+)"/g))
    add(match[1], entry.route);
}
for (const url of [
  "https://github.com/Laoch832/LyricWave",
  "https://github.com/Laoch832/LyricWave/releases",
  "https://api.github.com/repos/Laoch832/LyricWave/releases?per_page=20",
])
  add(url, "website");
// Include authentic release asset URLs when auditing a running website.
const base = process.env.AUDIT_BASE_URL;
if (base) {
  for (const route of ["/zh/download", "/en/download"]) {
    const response = await fetch(new URL(route, base));
    const html = await response.text();
    for (const match of html.matchAll(/<a\b[^>]*\bhref="(https?:[^"\s]+)"/g))
      add(match[1].replaceAll("&amp;", "&"), route);
  }
}
const entries = [...urls.entries()];
const results = [];
let cursor = 0;
async function request(url, method) {
  const response = await fetch(url, {
    method,
    redirect: "follow",
    signal: AbortSignal.timeout(12000),
    headers: { "User-Agent": "LyricWave-website-link-audit", Accept: "*/*" },
  });
  if (response.body) await response.body.cancel();
  return response;
}
async function worker() {
  while (cursor < entries.length) {
    const [url, sources] = entries[cursor++];
    let result;
    try {
      let response = await request(url, "HEAD");
      if ([400, 404, 405, 410, 501].includes(response.status))
        response = await request(url, "GET");
      const gated = new URL(url).pathname.startsWith(
        "/Laoch832/LyricWave/settings/",
      );
      const status = response.status;
      result = {
        url,
        status,
        finalUrl: response.url,
        state: response.ok
          ? "verified"
          : gated
            ? "authentication-required"
            : [404, 410].includes(status)
              ? "unavailable"
              : "unverified",
      };
    } catch (error) {
      result = {
        url,
        status: null,
        state: "unverified",
        reason: error.name === "TimeoutError" ? "timeout" : "network",
      };
    }
    results.push({ ...result, sources: [...sources] });
    if (results.length % 20 === 0)
      console.log(`Checked ${results.length}/${entries.length} external URLs`);
  }
}
await Promise.all(Array.from({ length: 4 }, worker));
results.sort((a, b) => a.url.localeCompare(b.url));
const counts = results.reduce(
  (acc, r) => ({ ...acc, [r.state]: (acc[r.state] || 0) + 1 }),
  {},
);
await fs.mkdir("artifacts/audit", { recursive: true });
await fs.writeFile(
  "artifacts/audit/external-links.json",
  JSON.stringify(
    {
      checkedAt: new Date().toISOString(),
      scope:
        "Generated documentation, official repository/API, and rendered release links when AUDIT_BASE_URL is set",
      counts,
      results,
    },
    null,
    2,
  ),
);
console.log(JSON.stringify(counts));
for (const row of results.filter((r) => r.state !== "verified"))
  console.log(`${row.state} ${row.status ?? row.reason}: ${row.url}`);
process.exitCode = counts.unavailable
  ? 1
  : counts.unverified || counts["authentication-required"]
    ? 2
    : 0;
