import fs from "node:fs/promises";
import path from "node:path";
import { unified } from "unified";
import remarkParse from "remark-parse";
import { visit } from "unist-util-visit";
import GithubSlugger from "github-slugger";
import { toString } from "mdast-util-to-string";
const map = JSON.parse(
  await fs.readFile("src/lib/generated/content-map.json", "utf8"),
);
const baseRoutes = new Set([
  "/zh",
  "/en",
  "/zh/download",
  "/en/download",
  "/docs/zh",
  "/docs/en",
]);
const pages = new Map(),
  errors = [];
for (const entry of map) {
  const base = "content/docs/" + entry.route.replace("/docs/", "");
  let text;
  try {
    text = await fs.readFile(base + ".md", "utf8");
  } catch {
    text = await fs.readFile(base + ".mdx", "utf8");
  }
  text = text.replace(/^---\n[\s\S]*?\n---\n/, "");
  const tree = unified().use(remarkParse).parse(text);
  const slugger = new GithubSlugger();
  const anchors = new Set();
  visit(tree, (n) => {
    if (n.type === "heading") anchors.add(slugger.slug(toString(n)));
    if (n.type === "html")
      for (const m of n.value.matchAll(/\bid="([^"]+)"/g)) anchors.add(m[1]);
  });
  pages.set(entry.route, { text, tree, anchors });
}
for (const [url, { tree, text }] of pages) {
  const urls = [];
  visit(tree, (n) => {
    if ("url" in n) urls.push(n.url);
  });
  for (const m of text.matchAll(/(?:href|src)="([^"]+)"/g)) urls.push(m[1]);
  for (const target of urls) {
    if (!target.startsWith("/") && !target.startsWith("#")) {
      if (!/^(https?:|mailto:|tel:)/i.test(target))
        errors.push(`${url}: unresolved relative link ${target}`);
      if (target.startsWith("https://github.com/Laoch832/LyricWave/")) {
        const file = new URL(target).pathname.match(
          /^\/Laoch832\/LyricWave\/(?:blob|tree)\/main\/(.*)$/,
        )?.[1];
        if (file) {
          try {
            await fs.access(path.resolve("..", decodeURIComponent(file)));
          } catch {
            errors.push(`${url}: missing repository source ${target}`);
          }
        }
      }
      continue;
    }
    const [pathname, hash] = target.split("#");
    if (pathname.startsWith("/media/")) {
      try {
        await fs.access(path.join("public", pathname));
      } catch {
        errors.push(`${url}: missing image ${target}`);
      }
      continue;
    }
    const dest = pathname ? pages.get(pathname) : pages.get(url);
    if (dest) {
      if (hash && !dest.anchors.has(decodeURIComponent(hash)))
        errors.push(`${url}: missing anchor ${target}`);
    } else if (!baseRoutes.has(pathname))
      errors.push(`${url}: missing route ${target}`);
  }
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log(
  `Checked ${pages.size} generated articles: local routes, images, and anchors are valid.`,
);
