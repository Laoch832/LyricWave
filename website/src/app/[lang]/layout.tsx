import { notFound } from "next/navigation";
import { isLocale } from "@/lib/i18n";
import { SiteProviders } from "@/components/site-providers";
export default async function LocaleLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  if (!isLocale(lang)) notFound();
  return <SiteProviders locale={lang}>{children}</SiteProviders>;
}
