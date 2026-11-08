import fs from "node:fs/promises";
const { host, key } = JSON.parse(
  await fs.readFile(new URL("../indexnow.json", import.meta.url), "utf8"),
);
const origin = `https://${host}`;
const keyLocation = `${origin}/${key}.txt`;
const keyResponse = await fetch(keyLocation, {
  signal: AbortSignal.timeout(20000),
});
if (!keyResponse.ok || (await keyResponse.text()).trim() !== key)
  throw new Error("Public IndexNow ownership file is not ready. Deploy first.");
const sitemapResponse = await fetch(`${origin}/sitemap.xml`, {
  signal: AbortSignal.timeout(20000),
});
if (!sitemapResponse.ok) throw new Error("Cannot read sitemap");
const xml = await sitemapResponse.text();
const urlList = [
  ...new Set(
    [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) =>
      m[1].replaceAll("&amp;", "&"),
    ),
  ),
];
if (
  !urlList.length ||
  urlList.length > 10000 ||
  urlList.some(
    (u) =>
      new URL(u).origin !== origin || new URL(u).pathname.startsWith("/api/"),
  )
)
  throw new Error(
    "Sitemap has no URLs, exceeds limit, or contains another origin/API URLs.",
  );
if (!process.argv.includes("--submit")) {
  console.log(
    JSON.stringify(
      {
        dryRun: true,
        endpoint: "https://api.indexnow.org/indexnow",
        host,
        count: urlList.length,
        keyLocation,
        urlList,
      },
      null,
      2,
    ),
  );
  process.exit(0);
}
const response = await fetch("https://api.indexnow.org/indexnow", {
  method: "POST",
  headers: { "Content-Type": "application/json; charset=utf-8" },
  body: JSON.stringify({ host, key, keyLocation, urlList }),
  signal: AbortSignal.timeout(30000),
});
const body = (await response.text()).slice(0, 2000);
const result = {
  time: new Date().toISOString(),
  host,
  count: urlList.length,
  status: response.status,
  accepted: [200, 202].includes(response.status),
  validationPending: response.status === 202,
  body,
};
await fs.mkdir("artifacts/seo", { recursive: true });
await fs.writeFile(
  "artifacts/seo/indexnow-submission.json",
  JSON.stringify(result, null, 2),
);
console.log(JSON.stringify(result, null, 2));
if (!result.accepted) process.exitCode = 1;
