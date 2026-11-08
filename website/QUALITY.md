# Website verification — 2026-11-07

当前生产发布前审计见 [FINAL_AUDIT.md](FINAL_AUDIT.md)；下文保留前两轮实施与视觉审查的历史结果。

Validated against a local **production build** using Node.js 24.22.1 and Chromium. No code was pushed, no Release was created, and no Vercel resources or production sites were changed.

| Check                                   | Result                                                                                                     |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `npm run build`                         | Passed: Next.js production compilation, MDX compilation and TypeScript                                     |
| `npm run typecheck`                     | Passed: route types and strict TypeScript                                                                  |
| `npm run check:links`                   | Passed: 58 generated articles, internal routes, images and anchors                                         |
| `npm test`                              | 4 passed: sorting/drafts, empty/malformed responses, asset origin validation, rate limits/network failures |
| `UPDATE_SCREENSHOTS=1 npm run test:e2e` | 8 passed                                                                                                   |
| Production HTTP routes                  | All 58 generated articles returned 200                                                                     |
| Accessibility                           | Main pages passed axe WCAG 2 A/AA and WCAG 2.1 A/AA checks                                                 |
| Existing repository inputs              | No changes to tracked Android sources, original docs, licenses or existing Android workflow                |

Browser tests verify desktop and mobile navigation, Escape/focus behavior, theme switching, English navigation, working Chinese search results, stable compiled anchors, official asset links or fallback, code copying to clipboard, pagination, reading progress, language selection and horizontal overflow. Review captures use 1440×1000 and 390×844 viewports, including dark and light themes.

The local mobile Lighthouse measurement after splitting marketing/documentation providers was **Performance 97 / Accessibility 100 / Best Practices 100 / SEO 100**, with LCP approximately **0.9 seconds** and total blocking time approximately **190 milliseconds**. These are local measurements, not production guarantees. Lighthouse also prompted a visible-name correction on the language control; the expanded final axe test includes the related WCAG 2.1 criterion and passes.

Screenshots and the raw Lighthouse report are available locally in the ignored `website/artifacts/` directory. Refresh captures with `UPDATE_SCREENSHOTS=1 npm run test:e2e`. Browser failure traces use `test-results/` and are also ignored.

## Limits

- English product pages, navigation, downloads and onboarding are available. Technical articles without an English original retain the authoritative Chinese source and explicitly disclose that an English translation is not yet available.
- Tests validate the website. They do not establish device-level Android, HDR, Apple Music or WeChat compatibility. Exact host eligibility remains derived from repository profiles and their per-capability evidence.
- GitHub API responses and assets can change. The download page handles unavailable data without inventing release information.
- Vercel deployment was not executed. Set `website/` as Root Directory and include source files outside it in the build step; see `README.md` for commands and environment configuration.

## Second design pass

The current presentation review and responsive verification are recorded in [VISUAL_REVIEW.md](VISUAL_REVIEW.md). The eight-test result above is the initial implementation baseline; the expanded design pass ran 15 browser tests, including six width/theme combinations.
