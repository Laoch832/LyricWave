import { clientUpdateResponse } from "@/lib/client-update-api";
import { options } from "@/lib/version-api";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET(request: Request) { return clientUpdateResponse(request); }
export const OPTIONS = options;
