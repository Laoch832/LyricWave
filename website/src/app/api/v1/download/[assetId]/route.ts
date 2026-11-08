import { downloadResponse, options } from "@/lib/version-api";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;
export async function GET(
  request: Request,
  context: { params: Promise<{ assetId: string }> },
) {
  return downloadResponse(request, (await context.params).assetId);
}
export const HEAD = GET;
export const OPTIONS = options;
