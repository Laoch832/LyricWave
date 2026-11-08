import { test, expect } from "@playwright/test";
import { dictionary } from "../../src/lib/i18n";
test("language links and extracted-document navigation resolve to real pages", async ({
  page,
}) => {
  await page.goto("/docs/en/start/introduction");
  await expect(page.locator(".source-language-notice")).toHaveCount(0);
  await expect(page.locator("#nd-page > .prose")).toHaveAttribute("lang", "en");
  await page
    .locator("#nd-page > .prose")
    .getByRole("link", { name: "简体中文", exact: true })
    .click();
  await expect(page).toHaveURL("/docs/zh/start/introduction");
  await page.goto("/docs/en/features/ai-lyrics");
  await expect(page.locator(".source-language-notice")).toBeVisible();
  await expect(page.locator("#nd-page > .prose")).toHaveAttribute(
    "lang",
    "zh-CN",
  );
  await page.goto("/docs/zh/advanced/architecture");
  await page
    .locator("#nd-page > .prose")
    .getByRole("link", { name: "文档目录", exact: true })
    .click();
  await expect(page).toHaveURL("/docs/zh/project/doc-index");
  await expect(page.locator("h1")).toContainText("原始文档索引");
});
test("documentation does not auto-fetch the animated GIF under reduced motion", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const gifRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/media/liquid-glass-demo.gif")
      gifRequests.push(request.url());
  });
  await page.goto("/docs/zh/features/player");
  await expect(page.locator(".glass-image img")).toBeVisible();
  expect(gifRequests).toEqual([]);
  await page.getByRole("button", { name: "播放实机演示", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "停止动图", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => gifRequests.length).toBe(1);
  await page.getByRole("button", { name: "停止动图", exact: true }).click();
  await expect(page.locator(".glass-image img")).not.toHaveAttribute(
    "src",
    "/media/liquid-glass-demo.gif",
  );
});
test("localized SEO, social previews, security headers and discovery routes are served", async ({
  page,
  request,
}) => {
  const response = await page.goto("/en");
  expect(response?.headers()["x-content-type-options"]).toBe("nosniff");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.locator('meta[property="og:description"]')).toHaveAttribute(
    "content",
    dictionary.en.intro,
  );
  await expect(page.locator('meta[property="og:locale"]')).toHaveAttribute(
    "content",
    "en_US",
  );
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    "href",
    /\/en$/,
  );
  expect((await page.title()).match(/LyricWave/g)).toHaveLength(1);
  const og = await request.get("/opengraph-image");
  expect(og.status()).toBe(200);
  expect(og.headers()["content-type"]).toContain("image/png");
  expect((await og.body()).length).toBeGreaterThan(1000);
  const sitemap = await request.get("/sitemap.xml");
  const xml = await sitemap.text();
  expect(xml).toContain("/docs/zh");
  expect(xml.match(/<url>/g)).toHaveLength(66);
  const robots = await request.get("/robots.txt");
  expect(await robots.text()).toContain("/sitemap.xml");
  const search = await request.get("/api/search?query=cache&locale=en");
  expect(search.status()).toBe(200);
  const results = await search.json();
  expect(results.length).toBeGreaterThan(0);
  for (const item of results) expect(item.url).toMatch(/^\/docs\/en\//);
});
