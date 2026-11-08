# LyricWave website

Independent Next.js App Router / TypeScript / Fumadocs documentation and product site. Android sources, existing `docs/`, signing, Gradle and release workflows remain authoritative and untouched.

## Local development

Use Node.js 24 and npm. Run from the repository checkout, **not a standalone copy of website/**:

```bash
cd website
npm ci
npm run dev
```

Open http://localhost:3000/zh or http://localhost:3000/en. Documentation opens at `/docs/zh` with a curated reading guide; original articles remain at their existing routes, including `/docs/zh/start/introduction`. `npm run dev` regenerates content before starting. After editing upstream Markdown, rerun `npm run sync:content`; development detects generated changes.

```bash
npm run build
npm start
npm run lint
npm run typecheck
npm run check:links
npm test
npm run check:repository
# Optional online link audit; authentication/rate-limit cases are reported separately.
NODE_USE_ENV_PROXY=1 npm run check:external
npx playwright install chromium
npm run test:e2e
```

Browser tests reuse a running server on port 3000 or start the production build automatically. Run `npm run build` first. `BASE_URL` can target a separately running preview. Linux browser dependencies may require `npx playwright install --with-deps chromium`. `artifacts/` contains review screenshots; `test-results/` contains failure details. Refresh screenshots with `UPDATE_SCREENSHOTS=1 npm run test:e2e`.

For managed environments whose network requires an inherited proxy, use Node 24's `NODE_USE_ENV_PROXY=1 npm start` (or `npm run dev`). TLS verification remains enabled. Normal Vercel hosting does not require this setting.

## Source of truth and content maintenance

`npm run sync:content` reads `../README.md`, `../README.en.md`, every Markdown document in `../docs/`, `../LICENSE`, `../THIRD_PARTY_NOTICES.md` and the indexed `../host-applemusic/src/main/resources/host-profiles/` JSON files. It copies repository images and makes a static WebP poster from the Liquid Glass GIF. It never writes to those inputs.

- `scripts/sync-content.mjs` owns grouping, source mapping, extracted sections and short onboarding guides. Existing technical articles are synchronized in full. Development and production-build scripts regenerate them; generated output is ignored by Git.
- `content/docs/{zh,en}/` is generated Markdown / MDX plus Fumadocs navigation metadata. **Do not edit it directly.**
- `src/lib/generated/content-map.json` records the source for every generated route. Each article links to its authoritative source on GitHub.
- `src/lib/generated/profiles.json` derives version names, build codes and production eligibility from the indexed profiles. A profile's existence or production flag is not a claim of complete runtime verification.
- Relative article links map to site routes; image links map to `/media/`; code, directory and other repository links map to GitHub. Stable explicit anchors are preserved by `remark-anchors.mjs`. `check:links` validates local article routes, images and anchors; browser tests additionally verify the compiled DOM.
- Chinese technical content remains authoritative. English navigation, landing page, downloads and onboarding are provided; technical articles without an English original show a clear Chinese-source notice. `englishSources` and `englishCustom` in the sync script explicitly define available English content; update these when adding a reviewed English source. Article body language follows the source, rather than guessing from brand names. No unreviewed automatic translation of host contracts is generated.
- Update facts in original README/docs/profile data first, then regenerate. If editing a short website onboarding guide in the sync script, check it against its listed source. Feature rules stay linked to detailed source documents.

The donation image is **Zen's**, not the fork maintainer's. Original attribution, source notices, license texts, and existing historical interface identity are retained. The waveform mark and lyric studies are original website visuals. Interactive demonstrations are explicitly marked as concepts and make no AI requests.

## Structure and design

- `src/app/[lang]/`: bilingual landing and download pages.
- `src/app/docs/[lang]/[[...slug]]/`: Fumadocs reader with sidebar, search, TOC, pagination and copyable code.
- `src/app/api/search/`: multilingual full-text index over generated content, scoped by locale.
- `src/components/`: separate lightweight marketing and documentation providers; narrowly scoped client islands for navigation, theme, lyric study, cache logic and opt-in GIF playback; shadcn-style Radix/CVA button in `ui/`.
- `src/app/globals.css`: graphite and paper design tokens, typography, spacing, materials, responsive layouts, focus and reduced-motion behavior.
- `src/lib/releases.ts`: validated GitHub API data with timeout, 30-minute Next data cache, release sorting and safe fallback.
- `tests/`, `scripts/check-links.mjs`: API policy tests, link validation and desktop/mobile browser coverage.

Fumadocs UI/Core 16.18.1, MDX 15.5.0, Next 16.4.5 and React 19.2 were checked against their published peer dependencies. `package-lock.json` pins the resolved dependency graph. Keep UI/Core at the same version when updating. No external fonts, analytics or 3D runtime are required.

## Release data and environment

The server reads `https://api.github.com/repos/Laoch832/LyricWave/releases?per_page=20`, rejects drafts, orders by actual publication time, and displays up to six releases with original GitHub assets. Prereleases are labeled; an empty response, malformed data, network timeout or API limit produces an official Releases link instead of fabricated packages. No APK is rehosted. Publication dates use Asia/Shanghai.

| Variable               | Required           | Purpose                                                                                                                                                                                                                                                                                           |
| ---------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `NEXT_PUBLIC_SITE_URL` | For production SEO | Public HTTPS origin, without trailing slash, used in canonical metadata, sitemap, robots and OG metadata. Falls back to Vercel’s `VERCEL_PROJECT_PRODUCTION_URL` when available, then local `http://localhost:3000`. An invalid origin fails the build instead of emitting broken canonical URLs. |
| `GITHUB_TOKEN`         | No                 | Server-only optional read token to increase GitHub API allowance. Never prefix with `NEXT_PUBLIC_`; no publishing rights are needed. Public API access works without it.                                                                                                                          |

No Android signing variables are consumed. No database is required.

## Vercel deployment

No live Vercel resources are created by this change. To deploy when ready:

1. Import `Laoch832/LyricWave` into a Vercel project with the Next.js preset.
2. Set **Root Directory** to `website` and enable **Include source files outside of the Root Directory in the Build Step**. Content synchronization requires the repository README/docs/license/profile files above `website/`. Use the full repository checkout, not a website-only upload.
3. Use Node.js 24, install command `npm ci`, build command `npm run build`, and the framework's default output directory. `website/vercel.json` supplies the commands; `next.config.mjs` supplies consistent security headers for both Vercel and a Node host.
4. Set `NEXT_PUBLIC_SITE_URL` to the intended public origin. Optionally configure server-only `GITHUB_TOKEN`. Rebuild after changing public metadata configuration.
5. Review a preview, then publish through your normal authorized process. Domain bindings, repository permissions, release settings and production sites are not changed by this implementation.

The website-only GitHub workflow checks relevant input changes; it does not sign APKs, publish packages, or deploy. Existing Android CI is unmodified.

GitHub Pages and pure static CDNs cannot execute the live search API and cached release fetch used here. Use Vercel or another Next.js Node host; static export is intentionally not configured. A static variant would need a separately designed static search index and release snapshot strategy.

## Audit tooling and boundaries

`npm run lint` uses the official Next.js 16.4.5 plugin, React Hooks rules, ESLint 10 and TypeScript 6.0.3. This TypeScript version is within the parser's supported range. The narrowly scoped minimatch overrides for ESLint, its config array and the TypeScript parser pin 11.0.1 to address the globparts amplification advisory; the modern exported matching API is used by these consumers, and lint/build checks are required when updating it. No production dependency relies on that override.

`check:links` rejects unresolved relative article links and verifies same-repository source targets locally. `check:external` checks generated-document and official GitHub URLs using bounded HTTP requests. Set `AUDIT_BASE_URL=http://localhost:3000` to include live download-page links/assets. Results are written to `artifacts/audit/external-links.json`; exit 1 means an unavailable public target, and exit 2 means an authentication-required or otherwise unverified target. It does not label rate limits or private GitHub settings as successfully checked. This online audit is intentionally separate from deterministic CI.

`check:repository` checks the current working tree for changes outside website files, the website workflow and the two README content sources, and scans non-ignored text files for common credential patterns. It prints locations only, never credential values. It is not an exhaustive secret-history audit and skips binary/large files. The only intended canonical-source edits in this audit correct translation-save wording in the Chinese and English READMEs; Android sources, `docs/`, licenses, signing and Android release workflows remain untouched.

Before a real Vercel deployment, set the actual public HTTPS origin (without a path) and enable inclusion of files outside `website/` in the build step. Local production builds validate the Next.js application, but do not prove remote project configuration or real-device Android compatibility.
