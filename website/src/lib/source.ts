import { docs } from "@source/server";
import { loader } from "fumadocs-core/source";
import { defineI18n } from "fumadocs-core/i18n";
export const i18n = defineI18n({
  defaultLanguage: "zh",
  languages: ["zh", "en"],
  parser: "dir",
});
export const source = loader({
  baseUrl: "/docs",
  url: (slugs, locale) => `/docs/${locale || "zh"}/${slugs.join("/")}`,
  source: docs.toFumadocsSource(),
  i18n,
});
