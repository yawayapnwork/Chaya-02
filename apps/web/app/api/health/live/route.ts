// Liveness only: the Node process answers HTTP. Never used to decide whether to route traffic (see ../route.ts).
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ status: "UP" }, { headers: { "Cache-Control": "no-store" } });
}
