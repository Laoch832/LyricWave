import { downloadResponse, options } from "@/lib/version-api";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;
export async function GET(request: Request, context: { params: Promise<{ assetId: string; fileName: string }> }) {
  const { assetId, fileName } = await context.params;
  return downloadResponse(request, assetId, undefined, undefined, fileName);
}
export const HEAD = GET;
export const OPTIONS = options;
