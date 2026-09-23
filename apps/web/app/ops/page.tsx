import { Suspense } from "react";
import OpsDashboard from "@/components/OpsDashboard";

export const metadata = { title: "Operations · Chaya 02" };

export default function OpsPage() {
  return (
    <Suspense fallback={<p className="p-8">Loading…</p>}>
      <OpsDashboard />
    </Suspense>
  );
}
