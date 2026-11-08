import { createHash, timingSafeEqual } from "node:crypto";
import { z } from "zod";
import { put, get, head, list, BlobNotFoundError } from "@vercel/blob";

const identifier = z.string().regex(/^[A-Za-z0-9_.$<>-]{1,180}$/);
const record = z.object({ id: z.string().uuid(), at: z.number().int().positive(),
  category: z.enum(["module", "playback", "share", "download", "update", "lyrics", "plugin"]),
  appVersion: z.string().regex(/^[0-9A-Za-z.-]{1,64}$/), appVersionCode: z.number().int().nonnegative().optional(), hostVersionCode: z.number().int().nonnegative().optional(), hostPackage: z.enum(["com.apple.android.music", "com.netease.cloudmusic", "dev.amenhancer.module", "dev.amenhancer.module.debug"]),
  hostVersion: z.string().regex(/^[0-9A-Za-z._-]{1,64}$/), sdk: z.number().int().min(26).max(99),
  codes: z.object({ native: z.number().int().optional(), component: z.number().int().optional(), http: z.number().int().optional(), state: z.number().int().optional(), result: z.number().int().optional() }).strict().optional(),
  exceptions: z.array(identifier).max(8), frames: z.array(z.object({ owner: identifier, method: identifier, line: z.number().int() }).strict()).max(32),
}).strict();
export const feedbackBatch = z.object({ schemaVersion: z.literal(1), batchId: z.string().uuid(), records: z.array(record).min(1).max(20) }).strict();
export type FeedbackBatch = z.infer<typeof feedbackBatch>;
export interface FeedbackStore {
  exists(path: string): Promise<boolean>;
  save(path: string, value: unknown): Promise<void>;
  read(date: string, cursor?: string): Promise<{ reports: unknown[]; cursor?: string }>;
}
const storage: FeedbackStore = {
  async exists(path) { try { await head(path); return true; } catch (e) { if (e instanceof BlobNotFoundError) return false; throw e; } },
  async save(path, value) { await put(path, JSON.stringify(value), { access: "private", addRandomSuffix: false, allowOverwrite: false, contentType: "application/json" }); },
  async read(date, cursor) {
    const page = await list({ prefix: `feedback/reports/${date}/`, limit: 20, cursor });
    const reports = [];
    for (const blob of page.blobs) {
      const response = await get(blob.pathname, { access: "private", useCache: false });
      if (response?.statusCode === 200) reports.push(await new Response(response.stream).json());
    }
    return { reports, cursor: page.hasMore ? page.cursor : undefined };
  },
};
const json = (body: unknown, status = 200, extra: Record<string, string> = {}) => Response.json(body, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...extra } });
async function boundedBody(request: Request) {
  if (!request.body) throw Error("empty");
  const reader = request.body.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try { while (true) { const item = await reader.read(); if (item.done) break;
    size += item.value.byteLength; if (size > 65536) throw Error("large"); chunks.push(item.value);
  } } finally { await reader.cancel(); }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
export async function receiveFeedback(request: Request, store: FeedbackStore = storage, configured = !!process.env.BLOB_READ_WRITE_TOKEN, now = Date.now()) {
  if (!configured) return json({ error: "FEEDBACK_UNAVAILABLE" }, 503);
  if (!request.headers.get("content-type")?.startsWith("application/json")) return json({ error: "INVALID_CONTENT_TYPE" }, 415);
  let data: FeedbackBatch;
  try { data = feedbackBatch.parse(await boundedBody(request)); } catch { return json({ error: "INVALID_FEEDBACK" }, 400); }
  if (data.records.some(r => r.at > now + 86400000 || r.at < now - 30 * 86400000)) return json({ error: "INVALID_TIMESTAMP" }, 400);
  const date = new Date(now).toISOString().slice(0, 10); const path = `feedback/reports/${date}/${data.batchId}.json`;
  try {
    if (await store.exists(path)) return json({ accepted: true, batchId: data.batchId }, 202);
    const address = request.headers.get("x-vercel-forwarded-for") ?? request.headers.get("x-forwarded-for") ?? "unknown";
    const bucket = createHash("sha256").update((process.env.FEEDBACK_ADMIN_TOKEN ?? "feedback") + address + Math.floor(now / 60000)).digest("hex");
    const rate = `feedback/rate/${date}/${bucket}.json`;
    if (await store.exists(rate)) return json({ error: "RATE_LIMITED" }, 429, { "Retry-After": "60" });
    await store.save(rate, { expiresAt: now + 60000 });
    await store.save(path, { receivedAt: now, ...data });
    return json({ accepted: true, batchId: data.batchId }, 202);
  } catch { return json({ error: "FEEDBACK_UNAVAILABLE" }, 503); }
}
export async function readFeedback(request: Request, store: FeedbackStore = storage, token = process.env.FEEDBACK_ADMIN_TOKEN) {
  const supplied = request.headers.get("authorization")?.replace(/^Bearer /, "") ?? "";
  if (!token || Buffer.byteLength(supplied) !== Buffer.byteLength(token) || !timingSafeEqual(Buffer.from(supplied), Buffer.from(token))) return json({ error: "UNAUTHORIZED" }, 401);
  const params = new URL(request.url).searchParams;
  const date = params.get("date") ?? new Date().toISOString().slice(0, 10); const cursor = params.get("cursor") ?? undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || (cursor?.length ?? 0) > 1024 || [...params.keys()].some(k => !["date", "cursor"].includes(k))) return json({ error: "INVALID_QUERY" }, 400);
  try { return json({ schemaVersion: 1, date, ...await store.read(date, cursor) }); }
  catch { return json({ error: "FEEDBACK_UNAVAILABLE" }, 503); }
}
