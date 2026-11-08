import { defineConfig, globalIgnores } from "eslint/config";
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import next from "@next/eslint-plugin-next";
import hooks from "eslint-plugin-react-hooks";
import globals from "globals";
export default defineConfig([
  globalIgnores([
    ".next/**",
    ".source/**",
    "content/**",
    "public/media/**",
    "src/lib/generated/**",
    "artifacts/**",
    "test-results/**",
    "next-env.d.ts",
  ]),
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  { files: ["src/**/*.{ts,tsx}"], ...next.configs["core-web-vitals"] },
  { files: ["src/**/*.{ts,tsx}"], ...hooks.configs.flat.recommended },
]);
