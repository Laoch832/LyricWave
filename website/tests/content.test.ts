import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { resolveSiteOrigin } from "../src/lib/site";
test("all original Markdown documents are present in the content map", async () => {
  const map = JSON.parse(
    await fs.readFile("src/lib/generated/content-map.json", "utf8"),
  ) as { source: string }[];
  for (const file of (await fs.readdir("../docs")).filter((file) =>
    file.endsWith(".md"),
  ))
    assert.ok(
      map.some((entry) => entry.source === `docs/${file}`),
      file,
    );
});
test("extracted sections resolve links, preserve language switching and use the English source", async () => {
  const architecture = await fs.readFile(
    "content/docs/zh/advanced/architecture.md",
    "utf8",
  );
  assert.match(architecture, /\]\(\/docs\/zh\/project\/doc-index\)/);
  assert.doesNotMatch(architecture, /\]\(docs\/README.md\)/);
  const en = await fs.readFile("content/docs/en/start/introduction.md", "utf8");
  assert.match(en, /简体中文\]\(\/docs\/zh\/start\/introduction\)/);
  const build = await fs.readFile("content/docs/en/advanced/build.md", "utf8");
  assert.match(build, /blob\/main\/README.en.md/);
});
test("host eligibility and the GPL text are derived without inventing compatibility or dropping notices", async () => {
  const profiles = JSON.parse(
    await fs.readFile("src/lib/generated/profiles.json", "utf8"),
  ) as {
    file: string;
    productionEnabled: boolean;
    version: string;
    build: number;
  }[];
  const index = JSON.parse(
    await fs.readFile(
      "../host-applemusic/src/main/resources/host-profiles/index.json",
      "utf8",
    ),
  );
  assert.equal(profiles.length, index.profiles.length);
  for (const profile of profiles) {
    const source = JSON.parse(
      await fs.readFile(
        "../host-applemusic/src/main/resources/host-profiles/" + profile.file,
        "utf8",
      ),
    );
    assert.equal(profile.productionEnabled, source.productionEnabled);
    assert.equal(profile.version, source.versionName);
    assert.equal(profile.build, source.versionCode);
  }
  const license = await fs.readFile("../LICENSE", "utf8");
  assert.ok(
    (await fs.readFile("content/docs/zh/project/license.md", "utf8")).includes(
      license,
    ),
  );
  const credits = await fs.readFile(
    "content/docs/zh/project/ACKNOWLEDGMENTS.md",
    "utf8",
  );
  assert.match(credits, /Zennmn/);
  assert.match(credits, /原作者.*Zen/);
});
test("public origins are normalized and credential-bearing or invalid origins fail clearly", () => {
  assert.equal(
    resolveSiteOrigin("https://example.org/"),
    "https://example.org",
  );
  assert.equal(
    resolveSiteOrigin(undefined, "lyricwave-example.vercel.app"),
    "https://lyricwave-example.vercel.app",
  );
  assert.equal(resolveSiteOrigin(), "http://localhost:3000");
  for (const value of [
    "https://u:p@example.org",
    "http://example.org",
    "https://example.org/docs",
    "https://example.org/?secret=value",
  ])
    assert.throws(() => resolveSiteOrigin(value));
});

test("content language follows the source, not Chinese brand names inside English prose", async () => {
  const entries = JSON.parse(
    await fs.readFile("src/lib/generated/content-map.json", "utf8"),
  ) as { route: string; contentLanguage: string }[];
  for (const route of [
    "/docs/en/start/introduction",
    "/docs/en/project/third-party",
    "/docs/en/advanced/localization",
  ])
    assert.equal(
      entries.find((e) => e.route === route)?.contentLanguage,
      "en",
      route,
    );
  assert.equal(
    entries.find((e) => e.route === "/docs/en/features/ai-lyrics")
      ?.contentLanguage,
    "zh",
  );
});
