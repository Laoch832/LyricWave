import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
const root = path.resolve("..");
const changed = execFileSync("git", ["diff", "--name-only", "HEAD", "-z"], {
  cwd: root,
  encoding: "utf8",
})
  .split("\0")
  .filter(Boolean);
const allowed = (file) =>
  file.startsWith("website/") ||
  ["README.md", "README.en.md", ".github/workflows/website.yml"].includes(file);
const newFiles = execFileSync(
  "git",
  ["ls-files", "--others", "--exclude-standard", "-z"],
  { cwd: root, encoding: "utf8" },
)
  .split("\0")
  .filter(Boolean);
const unexpected = [...changed, ...newFiles].filter((file) => !allowed(file));
const files = execFileSync(
  "git",
  ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
  { cwd: root, encoding: "utf8" },
)
  .split("\0")
  .filter(Boolean);
const patterns = [
  ["private-key", /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  [
    "github-token",
    /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b/,
  ],
  ["api-token", /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b/],
  ["aws-key-id", /\bAKIA[A-Z0-9]{16}\b/],
];
let scanned = 0,
  skipped = 0;
const findings = [];
for (const file of files) {
  const full = path.join(root, file);
  const stat = await fs.stat(full);
  if (!stat.isFile() || stat.size > 2 * 1024 * 1024) {
    skipped++;
    continue;
  }
  const bytes = await fs.readFile(full);
  if (bytes.subarray(0, 8192).includes(0)) {
    skipped++;
    continue;
  }
  scanned++;
  if (
    /(?:^|\/)(?:\.env(?:\..+)?|keystore\.properties)$/.test(file) &&
    !file.endsWith(".example")
  )
    findings.push({ file, kind: "sensitive-config-file" });
  const text = bytes.toString("utf8");
  for (const [kind, pattern] of patterns) {
    const match = pattern.exec(text);
    if (match)
      findings.push({
        file,
        kind,
        line: text.slice(0, match.index).split("\n").length,
      });
  }
}
const result = {
  checkedAt: new Date().toISOString(),
  scope:
    "Working tree tracked and non-ignored new text files; binary/large files and historical Git objects are not scanned",
  scanned,
  skipped,
  changed,
  newFileCount: newFiles.length,
  unexpected,
  findings,
};
await fs.mkdir("artifacts/audit", { recursive: true });
await fs.writeFile(
  "artifacts/audit/repository.json",
  JSON.stringify(result, null, 2),
);
console.log(
  JSON.stringify({ scanned, skipped, changed, unexpected, findings }),
);
if (unexpected.length || findings.length) process.exitCode = 1;
