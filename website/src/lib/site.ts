import type { Metadata } from "next";
import type { Locale } from "./i18n";
export function resolveSiteOrigin(
  explicit?: string,
  vercelDomain?: string,
): string {
  const url = new URL(
    explicit ||
      (vercelDomain ? `https://${vercelDomain}` : "http://localhost:3000"),
  );
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (
    (url.protocol !== "https:" && !(local && url.protocol === "http:")) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== "/"
  )
    throw new Error(
      "NEXT_PUBLIC_SITE_URL must be an HTTPS origin without credentials, path, query or fragment (HTTP localhost is allowed).",
    );
  return url.origin;
}
export const publicSiteOrigin = "https://lyricwave.canz-monkey.cn";
// Canonical identity is independent of the EdgeOne origin host and proxy headers.
export const siteOrigin = resolveSiteOrigin(
  process.env.NODE_ENV === "production"
    ? publicSiteOrigin
    : process.env.NEXT_PUBLIC_SITE_URL,
  process.env.VERCEL_PROJECT_PRODUCTION_URL,
);
export function pageMetadata({
  locale,
  title,
  description,
  path,
  alternates,
}: {
  locale: Locale;
  title: string;
  description: string;
  path: string;
  alternates?: Record<string, string>;
}): Metadata {
  const fullTitle = title.includes("LyricWave")
    ? title
    : `${title} · LyricWave`;
  return {
    title: { absolute: fullTitle },
    description,
    alternates: { canonical: path, languages: alternates },
    openGraph: {
      type: "website",
      siteName: "LyricWave",
      title: fullTitle,
      description,
      url: path,
      locale: locale === "zh" ? "zh_CN" : "en_US",
      alternateLocale: locale === "zh" ? ["en_US"] : ["zh_CN"],
      images: [
        {
          url: "/opengraph-image",
          width: 1200,
          height: 630,
          alt: "LyricWave — Every lyric. A deeper resonance.",
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title: fullTitle,
      description,
      images: ["/opengraph-image"],
    },
  };
}
