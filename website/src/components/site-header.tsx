"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTheme } from "next-themes";
import { useEffect, useRef, useState } from "react";
import {
  Menu,
  X,
  Moon,
  Sun,
  CodeXml,
  ArrowUpRight,
  Languages,
} from "lucide-react";
import { Brand } from "./brand";
import { dictionary, type Locale, repo } from "@/lib/i18n";
import { Button } from "./ui/button";
export function SiteHeader({ locale }: { locale: Locale }) {
  const t = dictionary[locale],
    pathname = usePathname();
  const { resolvedTheme, setTheme } = useTheme();
  const [open, setOpen] = useState(false),
    [scrolled, setScrolled] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const update = () => setScrolled(scrollY > 12);
    update();
    addEventListener("scroll", update, { passive: true });
    return () => removeEventListener("scroll", update);
  }, []);
  useEffect(() => {
    if (!open) return;
    const escape = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
        menuRef.current?.focus();
      }
    };
    addEventListener("keydown", escape);
    return () => removeEventListener("keydown", escape);
  }, [open]);
  const links = [
    { href: `/${locale}`, text: t.home },
    { href: `/${locale}#features`, text: t.features },
    { href: `/docs/${locale}`, text: t.docs },
    { href: `/${locale}/download`, text: t.download },
  ];
  return (
    <>
      <a className="skip-link" href="#main">
        {locale === "zh" ? "跳至正文" : "Skip to content"}
      </a>
      <header className={`site-header ${scrolled ? "scrolled" : ""}`}>
        <div className="header-inner">
          <Link href={`/${locale}`}>
            <Brand />
          </Link>
          <nav
            aria-label={locale === "zh" ? "主导航" : "Main navigation"}
            className="desktop-nav"
          >
            {links.map((l) => (
              <Link
                key={l.href}
                href={l.href}
                aria-current={
                  !l.href.includes("#") && pathname === l.href
                    ? "page"
                    : undefined
                }
              >
                {l.text}
              </Link>
            ))}
          </nav>
          <div className="header-tools">
            <Link
              className="language-control"
              href={pathname.replace(
                `/${locale}`,
                `/${locale === "zh" ? "en" : "zh"}`,
              )}
              aria-label={
                locale === "zh"
                  ? "EN — Switch to English"
                  : "中文 — 切换至简体中文"
              }
            >
              <Languages size={15} />
              <span>{locale === "zh" ? "EN" : "中文"}</span>
            </Link>
            <Button
              variant="ghost"
              size="icon"
              aria-label={
                locale === "zh" ? "切换深浅主题" : "Toggle color theme"
              }
              onClick={() =>
                setTheme(resolvedTheme === "dark" ? "light" : "dark")
              }
            >
              <Sun className="theme-sun" size={17} />
              <Moon className="theme-moon" size={17} />
            </Button>
            <a
              className="github-nav"
              href={repo}
              target="_blank"
              rel="noopener noreferrer"
            >
              <CodeXml size={17} />
              <span>GitHub</span>
              <ArrowUpRight size={12} />
            </a>
            <Button
              ref={menuRef}
              variant="ghost"
              size="icon"
              className="mobile-menu-button"
              aria-label={
                locale === "zh" ? "切换导航菜单" : "Toggle navigation menu"
              }
              aria-expanded={open}
              aria-controls="mobile-navigation"
              onClick={() => setOpen(!open)}
            >
              {open ? <X size={20} /> : <Menu size={20} />}
            </Button>
          </div>
        </div>
        {open && (
          <nav
            className="mobile-nav"
            id="mobile-navigation"
            aria-label="Mobile navigation"
          >
            {links.map((l) => (
              <Link key={l.href} href={l.href} onClick={() => setOpen(false)}>
                {l.text}
                <ArrowUpRight size={14} />
              </Link>
            ))}
          </nav>
        )}
      </header>
    </>
  );
}
