import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import fs from "node:fs/promises";
for (const width of [375, 768, 1440])
  for (const theme of ["dark", "light"]) {
    test(`visual review ${width}px ${theme}`, async ({ page }) => {
      test.setTimeout(90000);
      const errors: string[] = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript(
        (theme) => localStorage.setItem("theme", theme),
        theme,
      );
      await page.emulateMedia({ reducedMotion: "reduce" });
      for (const [name, route] of [
        ["home", "/zh"],
        ["overview", "/docs/zh"],
        ["article", "/docs/zh/advanced/apple-music-ttml-format"],
        ["installation", "/docs/zh/start/installation"],
        ["download", "/zh/download"],
        ["home-en", "/en"],
        ["overview-en", "/docs/en"],
      ] as const) {
        await page.goto(route);
        await expect(page.locator("h1")).toBeVisible();
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth - innerWidth,
          ),
          route,
        ).toBeLessThanOrEqual(1);
        if (name === "home" || name === "overview") {
          const result = await new AxeBuilder({ page })
            .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
            .analyze();
          expect(
            result.violations.map((v) => ({
              id: v.id,
              nodes: v.nodes.map((n) => n.target),
            })),
            route,
          ).toEqual([]);
        }
        if (process.env.UPDATE_SCREENSHOTS) {
          await fs.mkdir("artifacts/refinement/verified", { recursive: true });
          await page.screenshot({
            path: `artifacts/refinement/verified/${name}-${width}-${theme}.png`,
          });
        }
        if (name === "home") {
          await page.locator(".play-button").click();
          await expect(page.locator(".play-button")).toHaveAttribute(
            "aria-pressed",
            "true",
          );
          await page.locator(".glass-image").scrollIntoViewIfNeeded();
          await expect(page.locator(".play-button")).toHaveAttribute(
            "aria-pressed",
            "false",
          );
          await page
            .getByRole("button", { name: "播放实机演示", exact: true })
            .click();
          await expect(page.locator(".glass-image img")).toHaveAttribute(
            "src",
            "/media/liquid-glass-demo.gif",
          );
          await page.locator(".closing").scrollIntoViewIfNeeded();
          await expect(page.locator(".glass-image img")).not.toHaveAttribute(
            "src",
            "/media/liquid-glass-demo.gif",
          );
          if (process.env.UPDATE_SCREENSHOTS)
            await page.screenshot({
              path: `artifacts/refinement/verified/footer-${width}-${theme}.png`,
            });
        }
      }
      expect(errors).toEqual([]);
    });
  }
test("document sidebar survives article navigation; tablet can reclaim reading space", async ({
  page,
}) => {
  await page.setViewportSize({ width: 768, height: 1000 });
  await page.goto("/docs/zh/start/introduction");
  const group = page
    .locator("#nd-sidebar")
    .getByRole("button", { name: "功能指南", exact: true });
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await page
    .locator("#nd-sidebar")
    .getByRole("link", { name: "AI 歌词翻译、缓存", exact: false })
    .click();
  await expect(page).toHaveURL("/docs/zh/features/ai-lyrics");
  await expect(page.locator("#nd-page h1")).toContainText("AI 歌词翻译");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  const before = (await page.locator("#nd-page").boundingBox())!.width;
  await page
    .locator("#nd-sidebar")
    .getByRole("button", { name: "收起目录", exact: true })
    .click();
  await expect
    .poll(async () => (await page.locator("#nd-page").boundingBox())!.width)
    .toBeGreaterThan(before);
});
