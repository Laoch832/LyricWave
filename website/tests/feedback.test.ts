import { test } from "node:test";
import assert from "node:assert/strict";
import { receiveFeedback, readFeedback, type FeedbackStore } from "../src/lib/feedback-api";
const now = Date.now();
const row = { id: "e2a92862-cd09-479d-9ee6-e6b4207ba639", at: now, category: "playback", appVersion: "1.7.0", hostPackage: "com.apple.android.music", hostVersion: "7.0.0", sdk: 36, codes: { native: -25, component: -1 }, exceptions: ["DrmException"], frames: [{ owner: "NativeRenderer", method: "refresh", line: 10 }] };
const batch = { schemaVersion: 1, batchId: "e8f2c785-f7f4-47e1-bfe5-10206766db7f", records: [row] };
function storage(): FeedbackStore & { files: Map<string, unknown> } {
 const files = new Map<string, unknown>();
 return {
  files,
  async exists(path: string) { return files.has(path); },
  async save(path: string, value: unknown) { files.set(path, value); },
  async read() { return { reports: [...files.entries()].filter(([p]) => p.includes("/reports/")).map(([, v]) => v) }; },
 };
}

const post = (body: unknown) => new Request("https://x/api/v1/feedback", { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json" } });
test("feedback is durable, acknowledged and idempotent", async () => {
 const store = storage(); const first = await receiveFeedback(post(batch), store, true, now); assert.equal(first.status, 202); assert.equal((await first.json()).batchId, batch.batchId);
 assert.equal((await receiveFeedback(post(batch), store, true, now)).status, 202); assert.equal([...store.files.keys()].filter(p => p.includes("/reports/")).length, 1);
});
test("secrets and arbitrary message or URL fields are refused before storage", async () => {
 const store = storage(); for (const extra of [{ message: "private lyric and token" }, { url: "https://secret/?token=x" }, { apiKey: "sk-secret" }]) assert.equal((await receiveFeedback(post({ ...batch, records: [{ ...row, ...extra }] }), store, true, now)).status, 400);
 assert.equal(store.files.size, 0); assert.equal((await receiveFeedback(post(batch), store, false, now)).status, 503);
});
test("private feedback requires admin credentials and rate limits new batches", async () => {
 const store = storage(); await receiveFeedback(post(batch), store, true, now);
 assert.equal((await readFeedback(new Request("https://x/api/v1/feedback"), store, "admin-only")).status, 401);
 const result = await readFeedback(new Request("https://x/api/v1/feedback", { headers: { Authorization: "Bearer admin-only" } }), store, "admin-only"); assert.equal(result.status, 200); assert.equal((await result.json()).reports.length, 1);
 assert.equal((await receiveFeedback(post({ ...batch, batchId: "05be2d6d-a511-41b5-8bb3-aa8adb824895" }), store, true, now)).status, 429);
});

test("Unicode lookalike admin token is refused without throwing", async () => {
 const response = await readFeedback(new Request("https://x/api/v1/feedback", { headers: { Authorization: "Bearer ä" } }), storage(), "a");
 assert.equal(response.status, 401);
});
