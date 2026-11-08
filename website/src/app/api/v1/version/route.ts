import { versionResponse, options } from "@/lib/version-api";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  return versionResponse(request);
}
export const OPTIONS = options;
