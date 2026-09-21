// Liveness of the web app itself (does not check the backend).
export const dynamic = "force-dynamic";
export function GET() {
  return Response.json({ status: "UP" });
}
