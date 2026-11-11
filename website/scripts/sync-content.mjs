import fs from "node:fs/promises";
import { existsSync, statSync } from "node:fs";
import path from "node:path";
import sharp from "sharp";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkStringify from "remark-stringify";
import { toString } from "mdast-util-to-string";
import { visit } from "unist-util-visit";
const root = path.resolve("..");
const out = path.resolve("content/docs");
const github = "https://github.com/Laoch832/LyricWave";
const groups = {
  start: {
    zh: "入门",
    en: "Getting started",
    pages: [
      "introduction",
      "requirements",
      "installation",
      "scope",
      "migration",
      "faq",
    ],
  },
  features: {
    zh: "功能指南",
    en: "Feature guides",
    pages: [
      "ai-lyrics",
      "cache",
      "custom-lyrics",
      "online-lyrics",
      "hdr-and-moments",
      "player",
      "sharing",
      "playback-recovery",
      "native-playback-buffer",
    ],
  },
  advanced: {
    zh: "开发与架构",
    en: "Development",
    pages: [
      "plugin-author-guide",
      "plugin-development",
      "host-adaptation-guide",
      "host-adaptation-checklist",
      "apple-music-ttml-format",
      "architecture",
      "localization",
      "build",
      "ci-and-releases",
      "feature-preservation",
    ],
  },
  project: {
    zh: "项目",
    en: "Project",
    pages: [
      "ACKNOWLEDGMENTS",
      "third-party",
      "license",
      "contributing",
      "integration-2026-11",
      "releases",
      "doc-index",
    ],
  },
};
const mappings = {};
const docs = await fs.readdir(path.join(root, "docs"));
for (const f of docs.filter((f) => f.endsWith(".md"))) {
  const slug = f === "README.md" ? "doc-index" : f.slice(0, -3);
  const group = Object.keys(groups).find((g) => groups[g].pages.includes(slug));
  if (!group)
    throw new Error(
      `Unmapped source document: docs/${f}. Add it to the website content map.`,
    );
  mappings[`docs/${f}`] = `${group}/${slug}`;
}
mappings["THIRD_PARTY_NOTICES.md"] = "project/third-party";
mappings["LICENSE"] = "project/license";
mappings["README.md"] = "start/introduction";
mappings["README.en.md"] = "start/introduction";
const index = JSON.parse(
  await fs.readFile(
    path.join(
      root,
      "host-applemusic/src/main/resources/host-profiles/index.json",
    ),
    "utf8",
  ),
);
const profiles = await Promise.all(
  index.profiles.map(async (f) => {
    const p = JSON.parse(
      await fs.readFile(
        path.join(root, "host-applemusic/src/main/resources/host-profiles", f),
        "utf8",
      ),
    );
    if (
      typeof p.versionName !== "string" ||
      !Number.isInteger(p.versionCode) ||
      typeof p.productionEnabled !== "boolean"
    )
      throw new Error(`Invalid host profile identity: ${f}`);
    return {
      file: f,
      version: p.versionName,
      build: p.versionCode,
      productionEnabled: p.productionEnabled === true,
    };
  }),
);
await fs.mkdir("src/lib/generated", { recursive: true });
await fs.writeFile(
  "src/lib/generated/profiles.json",
  JSON.stringify(profiles, null, 2),
);
await fs.mkdir("public/media", { recursive: true });
for (const f of await fs.readdir(path.join(root, "docs/images")))
  await fs.copyFile(
    path.join(root, "docs/images", f),
    path.join("public/media", f),
  );
await sharp("public/media/liquid-glass-demo.gif")
  .resize({ width: 420 })
  .webp({ quality: 85 })
  .toFile("public/media/liquid-glass-poster.webp");
const processor = unified()
  .use(remarkParse)
  .use(remarkStringify, { fences: true, bullet: "-", listItemIndent: "one" });
function link(url, file, lang) {
  if (!url || /^(?:https?:|mailto:|tel:|#|\/)/i.test(url)) return url;
  const [target, hash] = url.split("#");
  const repoPath = path.posix.normalize(
    path.posix.join(path.posix.dirname(file), target),
  );
  if (repoPath === "README.en.md") return "/docs/en/start/introduction";
  if (repoPath === "README.md" && file === "README.en.md")
    return "/docs/zh/start/introduction";
  if (mappings[repoPath])
    return `/docs/${lang}/${mappings[repoPath]}${hash ? "#" + hash : ""}`;
  if (repoPath.startsWith("docs/images/"))
    return "/media/" + repoPath.slice(12);
  return `${github}/${(existsSync(path.join(root, repoPath)) && statSync(path.join(root, repoPath)).isDirectory()) || target.endsWith("/") ? "tree" : "blob"}/main/${repoPath}${hash ? "#" + hash : ""}`;
}
function transform(raw, file, lang) {
  raw = raw.replace(
    /<p align="center">[\s\S]*?<\/p>/g,
    "[![原作者 Zen 的微信赞赏码](/media/wechat-appreciation.png)](/media/wechat-appreciation.png)",
  );
  const tree = processor.parse(raw);
  if (
    tree.children[0]?.type === "html" &&
    tree.children[0].value.includes('id="top"')
  )
    tree.children.shift();
  const heading = tree.children.findIndex(
    (n) => n.type === "heading" && n.depth === 1,
  );
  if (heading >= 0) tree.children.splice(heading, 1);
  visit(tree, (n) => {
    if (n.type === "image" && /^https?:/.test(n.url)) {
      n.type = "text";
      n.value = n.alt || "Source badge";
      delete n.url;
      delete n.alt;
    }
    if ("url" in n) n.url = link(n.url, file, lang);
    if (n.type === "html") {
      n.value = n.value
        .replace(/(?:href|src)="([^"]+)"/g, (m, u) =>
          m.replace(u, link(u, file, lang)),
        )
        .replace(/<p align="center">/g, "<div>")
        .replace(/<\/p>/g, "</div>");
    }
  });
  return processor.stringify(tree);
}
const titles = {
  introduction: ["项目介绍", "Introduction"],
  requirements: ["系统要求与兼容性", "Requirements & compatibility"],
  installation: ["安装与启用", "Installation"],
  scope: ["模块作用域", "Module scope"],
  migration: ["从上游版本迁移", "Migration from upstream"],
  faq: ["常见问题", "FAQ"],
  cache: ["缓存与原歌词更新检测", "Cache & source updates"],
  "custom-lyrics": ["自定义 TTML 歌词", "Custom TTML lyrics"],
  "online-lyrics": ["在线歌词补全", "Online lyric completion"],
  player: ["液态玻璃与平板播放器", "Glass & tablet player"],
  sharing: ["朋友圈链接分享", "WeChat Moments links"],
  "native-playback-buffer": ["连续播放缓冲（实验）", "Continuous audio buffer (experimental)"],
  "integration-2026-11": ["上游合并记录", "Integration notes"],
  "playback-recovery": ["播放中断恢复", "Playback recovery"],
  architecture: ["项目架构", "Architecture"],
  build: ["从源码构建", "Build from source"],
  contributing: ["贡献指南", "Contributing"],
  releases: ["发布历史", "Release history"],
  "third-party": ["第三方许可与来源", "Third-party notices"],
  license: ["GNU GPL v3.0", "GNU GPL v3.0"],
  "doc-index": ["原始文档索引", "Source documentation index"],
};
const sourceBySlug = Object.fromEntries(
  Object.entries(mappings).map(([file, route]) => [
    route.split("/").at(-1),
    file,
  ]),
);
sourceBySlug.introduction = "README.md";
function excerpts(raw, heads) {
  const tree = processor.parse(raw);
  const nodes = [];
  let include = false;
  for (const n of tree.children) {
    if (n.type === "heading" && n.depth === 2) {
      const name = n.children.map((c) => c.value || "").join("");
      include = heads.includes(name);
    }
    if (include) nodes.push(n);
  }
  return processor.stringify({ type: "root", children: nodes });
}
const readme = await fs.readFile(path.join(root, "README.md"), "utf8");
const readmeEn = await fs.readFile(path.join(root, "README.en.md"), "utf8");
const profileTable = (lang) =>
  `| ${lang === "zh" ? "精确版本" : "Exact version"} | Build | ${lang === "zh" ? "生产启用" : "Production enabled"} |\n| --- | --- | --- |\n` +
  profiles
    .map(
      (p) =>
        `| ${p.version} | ${p.build} | ${p.productionEnabled ? (lang === "zh" ? "是" : "Yes") : lang === "zh" ? "否（仅参考档案）" : "No (reference only)"} |`,
    )
    .join("\n");
const custom = {
  requirements: (l) =>
    l === "zh"
      ? `需要 **Android 8.0+**、受支持的 Apple Music 精确构建，以及支持 **libxposed API 102** 的 LSPosed 或兼容框架。\n\n## 精确宿主档案\n\n${profileTable(l)}\n\n生产启用不代表所有能力已通过当前设备的运行验证。每项能力独立报告可用、缺失或歧义；请核对 [原始档案](${github}/tree/main/host-applemusic/src/main/resources/host-profiles) 中的 evidence、runtimeVerified 和 knownDegradations。未知构建不推定兼容。\n\n## 功能边界\n\n真正的 HDR 需要 Android 14+、HDR 屏幕和硬件加速。双向歌词模糊适用于 Android 12 起且独立契约完整的构建。朋友圈链接分享使用网易云音乐原生分享流程，需要 Apple Music 和网易云音乐作用域；不要勾选微信。版本与真机验证边界见分享指南。`
      : `Requires **Android 8.0+**, an exact supported Apple Music build, and LSPosed or a compatible framework with **libxposed API 102**.\n\n## Exact host profiles\n\n${profileTable(l)}\n\nProduction eligibility is distinct from runtime verification of each capability. Review evidence, runtimeVerified, and knownDegradations in the [original profiles](${github}/tree/main/host-applemusic/src/main/resources/host-profiles). Unknown builds are not assumed compatible.\n\n## Feature boundaries\n\nGenuine HDR needs Android 14+, an HDR display, and hardware acceleration. Bidirectional blur needs Android 12+ and a complete blur contract. Moments links use NetEase Cloud Music’s native sharing flow. Select Apple Music and NetEase Cloud Music scope, never WeChat. See the sharing guide for version evidence and device verification limits.`,
  installation: (l) =>
    `<Callout title="${l === "zh" ? "安装前备份" : "Back up before migrating"}" type="warn">${l === "zh" ? "本分支与上游原版签名不同。首次迁移需先备份设置和自定义歌词，再卸载原版。" : "This fork uses a different signing key. Back up settings and custom lyrics before uninstalling upstream."}</Callout>\n\n<Steps>\n<Step>\n### ${l === "zh" ? "下载安装包" : "Download the APK"}\n${l === "zh" ? "从本项目官方 Releases 获取 APK，不要将上游嵌入版当作本分支安装包。" : "Get an APK from this fork’s official releases. The upstream embedded edition is a separate distribution."}\n\n[${l === "zh" ? "前往下载页面" : "Open downloads"}](/${l}/download)\n</Step>\n<Step>\n### ${l === "zh" ? "启用与配置作用域" : "Enable and set scope"}\n${l === "zh" ? "在 LSPosed 中启用模块，勾选 Apple Music。朋友圈链接分享额外勾选网易云音乐，取消微信。" : "Enable the module in LSPosed. Select Apple Music and additionally NetEase Cloud Music for Moments links. Deselect WeChat."}\n</Step>\n<Step>\n### ${l === "zh" ? "重新启动宿主" : "Restart the host"}\n${l === "zh" ? "强制停止并重新打开 Apple Music。在 Apple Music → 设置 → 澜音模块设置 中管理功能。模块没有独立启动图标。" : "Force stop and reopen Apple Music. Open Apple Music → Settings → LyricWave module settings. The module has no separate launcher icon."}\n</Step>\n</Steps>`,
  scope: (l) =>
    `## ${l === "zh" ? "作用域配置" : "Scope configuration"}\n\n<Tabs items={["Apple Music", "NetEase Cloud Music"]}>\n<Tab value="Apple Music">\n\n\`com.apple.android.music\`\n\n${l === "zh" ? "在 LSPosed 中启用模块并选择 Apple Music，强制停止后重新打开。" : "Enable the module for Apple Music in LSPosed, then force stop and reopen the host."}\n\n</Tab>\n<Tab value="NetEase Cloud Music">\n\n\`com.netease.cloudmusic\`\n\n${l === "zh" ? "仅在使用朋友圈链接分享时额外勾选网易云音乐，取消微信。配置后重开 Apple Music 和网易云音乐；旧版曾加载微信桥接时也需重开微信。分享由用户确认，不自动发布。" : "Additionally select NetEase Cloud Music for Moments links and deselect WeChat. Restart Apple Music and NetEase Cloud Music; also restart WeChat if an older bridge was loaded. Sharing requires user confirmation and never publishes automatically."}\n\n</Tab>\n</Tabs>`,
  migration: (l) =>
    l === "zh"
      ? `## 签名与数据\n\n本项目使用独立固定签名。先备份设置与自定义歌词，再卸载上游原版并安装本分支。同签名的本分支版本支持覆盖升级。\n\n安装身份、插件命名空间、已有存储键与签名配置保持兼容。不要为修复安装失败自行更换密钥。\n\n[完整签名与发布说明](/docs/zh/advanced/ci-and-releases)`
      : `## Signing and data\n\nThis fork has its own fixed signing key. Back up settings and custom lyrics, uninstall upstream, then install this fork. Later builds with the same fork signature allow in-place updates.\n\nThe installation ID, plugin namespaces, and existing storage keys stay compatible. Do not replace keys as an installation fix.\n\n[Signing and release reference](/docs/en/advanced/ci-and-releases)`,
  faq: (l) =>
    l === "zh"
      ? `## 为什么找不到应用图标？\n\n模块没有独立启动图标。启用后进入 Apple Music → 设置 → 澜音模块设置。\n\n## 安装后没有生效？\n\n核对精确宿主构建、libxposed API 102 与模块作用域。强制停止并重新打开 Apple Music，部分设置也需重启。\n\n## AI 翻译为什么跳过？\n\n已有译文、中文或无逐行/逐字时间轴的歌词会保留。检查 [AI 翻译指南](/docs/zh/features/ai-lyrics) 的跳过原因和请求记录。\n\n## 旧译文为什么更新？\n\n当前选中原文 TTML 的 SHA-256、接口、模型或有效思考配置变化会使缓存失效。只更换 API Key 不影响复用。\n\n## 如何反馈？\n\n在 [Issues](${github}/issues) 提供设备、Android 与 Apple Music 精确版本和复现步骤。不要提交 API Key。`
      : `## Where is the launcher icon?\n\nThere is no separate icon. Open Apple Music → Settings → LyricWave module settings after enabling the module.\n\n## Why is it inactive?\n\nCheck the exact host build, libxposed API 102, and module scope. Force stop and reopen Apple Music. Some settings also need a restart.\n\n## Why was translation skipped?\n\nExisting translations, Chinese lyrics, and untimed lyrics are preserved. Check the [AI guide](/docs/en/features/ai-lyrics).\n\n## Why was a cached translation replaced?\n\nChanges to source TTML, endpoint, model, or effective thinking configuration invalidate it. API key rotation alone does not.\n\n## How can I report an issue?\n\nInclude device, Android, exact Apple Music build, and reproduction steps in [Issues](${github}/issues). Never submit API keys.`,
  cache: () => excerpts(awaitReadAi, ["更新与复用", "调用记录与统计口径"]),
  "custom-lyrics": () =>
    `## 导入、编辑与备份\n\n打开自定义歌词并启用替换，读取当前歌曲 ID，粘贴或导入 TTML，也可从 AMLL、AM-Lyrics 或 Lunabeat 获取。保存并启用后，按设置提示重启 Apple Music。\n\n支持搜索、编辑、删除和 ZIP 备份恢复。手动歌词与 AI 翻译缓存分别保存，删除翻译缓存不删除手动歌词。\n\n[TTML 格式说明](/docs/zh/advanced/apple-music-ttml-format) · [来源与候选处理](/docs/zh/advanced/host-adaptation-guide#lyrics-sources)`,
  "online-lyrics": () =>
    `## 补全条件\n\n在线补全可为缺词、非逐字或缺翻译的歌曲查找歌词。可以独立关闭，不是 AI 翻译的前提。\n\n最终选中的手动、在线或原生歌词才是翻译与更新检测的依据。来源刷新按在线策略执行，不等于单独轮询原生服务器。\n\n[来源处理与能力边界](/docs/zh/advanced/host-adaptation-guide#lyrics-sources)`,
  player: () =>
    `## 液态玻璃\n\n在对应设置中启用液态玻璃，可用于导航与迷你播放器。部分改动需要强制停止并重新打开 Apple Music。底栏收缩仅面向已登记的 7.0.0-beta 手机布局；平板与旧版保持展开。\n\n<GlassDemo />\n\n## 平板双栏\n\n横屏并排展示播放控件与实时歌词。启用资格与已知降级按精确宿主档案区分，不推定所有构建支持相同布局。\n\n![仓库平板双栏实机素材](/media/tablet-dual-pane-player-open-source-blur.jpg)\n\n[播放器与玻璃验收矩阵](/docs/zh/advanced/feature-preservation)`,
  sharing: () => excerpts(awaitReadHdr, ["朋友圈：必须是可点击链接"]),
  architecture: () =>
    excerpts(readme, ["项目结构与文档"]) +
    `\n[详细装配与执行链](/docs/zh/advanced/host-adaptation-guide#architecture)`,
  build: (l) =>
    excerpts(l === "en" ? readmeEn : readme, [
      l === "en" ? "Building from source" : "从源码构建",
    ]),
  contributing: (l) =>
    excerpts(l === "en" ? readmeEn : readme, [
      l === "en" ? "Contributing, privacy, and licenses" : "反馈与贡献",
    ]),
  releases: (l) =>
    `## ${l === "zh" ? "官方发布记录" : "Official release history"}\n\n${l === "zh" ? "发布记录和真实安装包来自本项目 GitHub Releases。网站下载页按发布时间排序，区分正式版与预发布，不硬编码“最新”版本。" : "Release notes and assets come from this project’s GitHub Releases. Downloads are ordered by publication date and distinguish prereleases; no latest version is hardcoded."}\n\n[${l === "zh" ? "查看版本与安装包" : "View releases and assets"}](/${l}/download) · [GitHub Releases](${github}/releases)`,
};
const awaitReadAi = await fs.readFile(
  path.join(root, "docs/ai-lyrics.md"),
  "utf8",
);
const awaitReadHdr = await fs.readFile(
  path.join(root, "docs/hdr-and-moments.md"),
  "utf8",
);
await fs.rm(out, { recursive: true, force: true });
const manifest = [];
const englishSources = new Set([
  "README.en.md",
  "THIRD_PARTY_NOTICES.md",
  "docs/localization.md",
  "LICENSE",
]);
const englishCustom = new Set([
  "requirements",
  "installation",
  "scope",
  "migration",
  "faq",
  "build",
  "contributing",
  "releases",
]);
for (const lang of ["zh", "en"]) {
  const dir = path.join(out, lang);
  await fs.mkdir(dir, { recursive: true });
  await fs.writeFile(
    path.join(dir, "meta.json"),
    JSON.stringify({ title: "LyricWave", pages: Object.keys(groups) }),
  );
  for (const [group, info] of Object.entries(groups)) {
    await fs.mkdir(path.join(dir, group), { recursive: true });
    await fs.writeFile(
      path.join(dir, group, "meta.json"),
      JSON.stringify({
        title: info[lang],
        pages: info.pages,
        defaultOpen: group === "start",
      }),
    );
    for (const slug of info.pages) {
      let file = sourceBySlug[slug] || "README.md";
      let raw;
      let title;
      let isMdx = false;
      if (custom[slug]) {
        raw = custom[slug](lang);
        if (lang === "en" && ["build", "contributing"].includes(slug))
          file = "README.en.md";
        title = titles[slug]?.[lang === "en" ? 1 : 0];
        isMdx = ["installation", "scope", "player"].includes(slug);
        if (["cache", "sharing"].includes(slug))
          file =
            slug === "cache" ? "docs/ai-lyrics.md" : "docs/hdr-and-moments.md";
      } else {
        if (slug === "introduction" && lang === "en") file = "README.en.md";
        raw = await fs.readFile(path.join(root, file), "utf8");
        title =
          titles[slug]?.[lang === "en" ? 1 : 0] ||
          raw.match(/^# (.+)/m)?.[1] ||
          slug;
        raw =
          slug === "license"
            ? "```text\n" + raw + "\n```"
            : transform(raw, file, lang);
      }
      if (!title) title = slug;
      if (custom[slug]) {
        if (lang === "en") raw = raw.replaceAll("/docs/zh/", "/docs/en/");
        if (!isMdx) raw = transform(raw, file, lang);
      }
      const sourceLink = `${github}/blob/main/${file}`;
      const contentLanguage = custom[slug]
        ? lang === "en" && englishCustom.has(slug)
          ? "en"
          : "zh"
        : englishSources.has(file)
          ? "en"
          : "zh";
      const lead = processor
        .parse(raw)
        .children.find(
          (node) =>
            node.type === "paragraph" && toString(node).trim().length >= 30,
        );
      const description = lead
        ? toString(lead).replace(/\s+/g, " ").trim().slice(0, 155)
        : `${title} · LyricWave`;
      const header = `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(description)}\n---\n\n`;
      await fs.writeFile(
        path.join(dir, group, slug + (isMdx ? ".mdx" : ".md")),
        header +
          raw +
          `\n\n---\n\n[${lang === "zh" ? "在 GitHub 查看事实来源" : "View authoritative source on GitHub"}](${sourceLink})\n`,
      );
      manifest.push({
        lang,
        route: `/docs/${lang}/${group}/${slug}`,
        source: file,
        contentLanguage,
      });
    }
  }
}
await fs.writeFile(
  "src/lib/generated/content-map.json",
  JSON.stringify(manifest, null, 2),
);
console.log(
  `Synced ${manifest.length} pages, ${profiles.length} exact host profiles. Original docs are untouched.`,
);
