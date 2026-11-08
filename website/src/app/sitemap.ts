import { siteOrigin } from "@/lib/site";
import type { MetadataRoute } from "next";
import { source } from "@/lib/source";
export default function sitemap(): MetadataRoute.Sitemap {
  const base = siteOrigin;
  return [
    ...["/zh", "/en", "/zh/download", "/en/download", "/docs/zh", "/docs/en"],
    ...source.getPages("zh").map((p) => p.url),
    ...source.getPages("en").map((p) => p.url),
  ].map((url) => ({ url: base + url }));
}
