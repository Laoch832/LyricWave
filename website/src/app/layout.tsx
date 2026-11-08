import { siteOrigin } from "@/lib/site";
import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";
export const metadata: Metadata = {
  metadataBase: new URL(siteOrigin),
  title: {
    default: "澜音 LyricWave — 每一句，都有回响",
    template: "%s · LyricWave",
  },
  description:
    "Apple Music Android 增强项目。AI 歌词翻译、TTML、液态玻璃与平板双栏播放器。",
  openGraph: {
    type: "website",
    siteName: "LyricWave",
    images: ["/opengraph-image"],
  },
  twitter: { card: "summary_large_image" },
};
export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale =
    (await headers()).get("x-lyricwave-locale") === "en" ? "en" : "zh-CN";
  return (
    <html lang={locale} suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}
