import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import fs from "node:fs/promises";
import manifest from "../../src/lib/generated/content-map.json" with { type: "json" };

test("desktop: real interactions, theme and bilingual navigation", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/zh");
  await expect(page.locator(".lyric-scene")).toBeVisible();
  await page.getByRole("button", { name: "下一句", exact: true }).click();
  await expect(page.locator(".lyric-line.active")).toContainText(
    "Let the words",
  );
  await page.getByRole("button", { name: "模拟原歌词更新" }).click();
  await expect(page.locator(".cache-result")).toContainText("原文已变化");
  await page.getByRole("button", { name: "切换深浅主题" }).click();
  await expect(page.locator("html")).not.toHaveClass(/dark/);
  await page.getByRole("button", { name: "切换深浅主题" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByRole("link", { name: /Switch to English/ }).click();
  await expect(page).toHaveURL("/en");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.locator("h1")).toContainText("Every lyric.");
  expect(errors).toEqual([]);
});
test("mobile menu, escape, document tabs and no horizontal overflow", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/zh");
  await page.getByRole("button", { name: "切换导航菜单" }).click();
  await expect(page.locator("#mobile-navigation")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("#mobile-navigation")).toHaveCount(0);
  await page.getByRole("button", { name: "切换导航菜单" }).click();
  await page
    .locator("#mobile-navigation")
    .getByRole("link", { name: "文档" })
    .click();
  await expect(page.locator("h1")).toContainText("项目文档");
  await page.goto("/docs/zh/start/scope");
  await page.getByRole("tab", { name: "NetEase Cloud Music", exact: true }).click();
  await expect(page.getByRole("tabpanel")).toContainText("com.netease.cloudmusic");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth - innerWidth,
    ),
  ).toBeLessThanOrEqual(1);
});
test("compiled articles, stable anchors and local navigation", async ({
  page,
  request,
}) => {
  for (const entry of manifest) {
    const response = await request.get(entry.route);
    expect(response.status(), entry.route).toBe(200);
  }
  await page.goto("/docs/zh/advanced/host-adaptation-guide#architecture");
  await expect(page.locator("#architecture")).toHaveCount(1);
  await expect(page.locator("h1")).toHaveCount(1);
  const broken = await page
    .locator('a[href^="#"]')
    .evaluateAll((links) =>
      links
        .map((a) => a.getAttribute("href")!)
        .filter(
          (h) =>
            h.length > 1 &&
            !document.getElementById(decodeURIComponent(h.slice(1))),
        ),
    );
  expect(broken).toEqual([]);
  await expect(
    page
      .locator('a[href="/docs/zh/advanced/host-adaptation-checklist"]')
      .first(),
  ).toBeVisible();
});
test("Chinese full-text search returns working localized routes", async ({
  page,
}) => {
  await page.goto("/docs/zh/features/ai-lyrics");
  await page.keyboard.press("Control+k");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("dialog").locator("input").fill("缓存");
  const result = page.getByRole("dialog").getByRole("option").first();
  await expect(result).toBeVisible();
  await result.click();
  await expect(page).toHaveURL(/\/docs\/zh\//);
  await expect(page.locator("h1")).toBeVisible();
});
test("downloads have official assets or a usable official fallback", async ({
  page,
}) => {
  await page.goto("/zh/download");
  await expect(page.locator("h1")).toContainText("版本与下载");
  await expect(
    page
      .locator('a[href="https://github.com/Laoch832/LyricWave/releases"]')
      .first(),
  ).toBeVisible();
  for (const href of await page
    .locator(".asset-link")
    .evaluateAll((a) => a.map((el) => el.getAttribute("href"))))
    expect(href).toMatch(
      /^https:\/\/github.com\/Laoch832\/LyricWave\/releases\/download\//,
    );
});
test("main pages meet automated WCAG A/AA checks", async ({ page }) => {
  for (const route of [
    "/zh",
    "/en",
    "/docs/zh/start/installation",
    "/zh/download",
  ]) {
    await page.goto(route);
    await page.waitForTimeout(900);
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(
      results.violations.map((v) => ({
        id: v.id,
        nodes: v.nodes.map((n) => n.target),
      })),
      route,
    ).toEqual([]);
  }
});
test("desktop and mobile visual review captures", async ({ page }) => {
  test.skip(!process.env.UPDATE_SCREENSHOTS, "Opt-in visual artifacts");
  await page.addInitScript(() => localStorage.setItem("theme", "dark"));
  await fs.mkdir("artifacts", { recursive: true });
  for (const [name, width, height] of [
    ["desktop", 1440, 1000],
    ["mobile", 390, 844],
  ] as const) {
    await page.setViewportSize({ width, height });
    for (const [key, route] of [
      ["home", "/zh"],
      ["docs", "/docs/zh/features/ai-lyrics"],
      ["download", "/zh/download"],
    ] as const) {
      await page.goto(route);
      await page.waitForTimeout(1200);
      await page.screenshot({
        path: `artifacts/${key}-${name}.png`,
        fullPage: true,
      });
    }
    await page.goto("/en");
    await page.getByRole("button", { name: "Toggle color theme" }).click();
    await page.waitForTimeout(500);
    await page.screenshot({
      path: `artifacts/home-light-${name}.png`,
      fullPage: true,
    });
  }
});

test("reader code copy, pagination, progress and language switch", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/docs/zh/advanced/apple-music-ttml-format");
  await page
    .getByRole("button", { name: "复制代码", exact: true })
    .first()
    .click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
    "<tt",
  );
  await page.evaluate(() => scrollTo(0, document.documentElement.scrollHeight));
  await expect(page.locator(".reading-progress")).not.toHaveCSS(
    "transform",
    "matrix(0, 0, 0, 1, 0, 0)",
  );
  await expect(
    page.locator('a[href="/docs/zh/advanced/architecture"]').last(),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "简体中文 · 选择语言", exact: true })
    .click();
  await page.getByRole("button", { name: "English", exact: true }).click();
  await expect(page).toHaveURL("/docs/en/advanced/apple-music-ttml-format");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});
