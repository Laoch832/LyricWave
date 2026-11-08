import { z } from "zod";
import { repo } from "./i18n";
const safeGitHubUrl = (kind: "page" | "asset") =>
  z
    .string()
    .url()
    .refine((value) => {
      try {
        const url = new URL(value);
        const prefix =
          kind === "page"
            ? "/Laoch832/LyricWave/releases/tag/"
            : "/Laoch832/LyricWave/releases/download/";
        return (
          url.origin === "https://github.com" &&
          !url.username &&
          !url.password &&
          !url.search &&
          !url.hash &&
          url.pathname.startsWith(prefix) &&
          url.pathname.length > prefix.length
        );
      } catch {
        return false;
      }
    });
const releaseSchema = z.object({
  id: z.number(),
  name: z.string().nullable(),
  tag_name: z.string(),
  published_at: z.string().datetime().nullable(),
  html_url: safeGitHubUrl("page"),
  body: z.string().nullable(),
  draft: z.boolean(),
  prerelease: z.boolean(),
  assets: z.array(
    z.object({
      id: z.number(),
      name: z.string(),
      size: z.number().int().nonnegative(),
      browser_download_url: safeGitHubUrl("asset"),
    }),
  ),
});
export type Release = z.infer<typeof releaseSchema>;
export type ReleasesResult =
  | { ok: true; releases: Release[] }
  | { ok: false; reason: "network" | "rate-limit" | "invalid" | "empty" };
export function parseReleases(input: unknown): ReleasesResult {
  const parsed = z.array(releaseSchema).safeParse(input);
  if (!parsed.success) return { ok: false, reason: "invalid" };
  const releases = parsed.data
    .filter((r) => !r.draft && r.published_at)
    .sort((a, b) => Date.parse(b.published_at!) - Date.parse(a.published_at!))
    .slice(0, 6);
  return releases.length
    ? { ok: true, releases }
    : { ok: false, reason: "empty" };
}
export async function getReleases(): Promise<ReleasesResult> {
  try {
    const response = await fetch(
      "https://api.github.com/repos/Laoch832/LyricWave/releases?per_page=20",
      {
        headers: {
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          ...(process.env.GITHUB_TOKEN
            ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` }
            : {}),
        },
        next: { revalidate: 1800, tags: ["github-releases"] },
        signal: AbortSignal.timeout(8000),
      },
    );
    if (!response.ok)
      return {
        ok: false,
        reason:
          response.status === 403 || response.status === 429
            ? "rate-limit"
            : "network",
      };
    return parseReleases(await response.json());
  } catch (error) {
    return {
      ok: false,
      reason: error instanceof SyntaxError ? "invalid" : "network",
    };
  }
}
export const releasesUrl = `${repo}/releases`;

export function formatAssetSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
