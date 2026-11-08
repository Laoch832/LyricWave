"use client";
import { chineseUI } from "@/lib/ui-translations";
import { RootProvider } from "fumadocs-ui/provider/next";
import { useRouter, usePathname } from "next/navigation";
import { useEffect } from "react";
export function Providers({
  children,
  locale,
}: {
  children: React.ReactNode;
  locale: "en" | "zh";
}) {
  const path = usePathname();
  const router = useRouter();
  useEffect(() => {
    document.documentElement.lang = locale === "zh" ? "zh-CN" : "en";
  }, [locale]);
  return (
    <RootProvider
      theme={{ defaultTheme: "dark" }}
      search={{ options: { api: "/api/search" } }}
      i18n={{
        locale,
        onLocaleChange: (next) =>
          router.push(
            path
              .replace(`/docs/${locale}`, `/docs/${next}`)
              .replace(new RegExp(`^/${locale}(?=/|$)`), `/${next}`),
          ),
        locales: [
          { locale: "zh", name: "简体中文" },
          { locale: "en", name: "English" },
        ],
        translations:
          locale === "zh"
            ? chineseUI
            : {
                "Choose a language(language switcher)(aria-label)":
                  "English · Choose a language",
              },
      }}
    >
      {children}
    </RootProvider>
  );
}
