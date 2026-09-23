import { Suspense } from "react";
import ArWorkspace from "@/components/ArWorkspace";

export const metadata = { title: "AR navigation · Chaya 02" };

export default function ArPage() {
  return (
    <Suspense fallback={<p className="p-8">Loading…</p>}>
      <ArWorkspace />
    </Suspense>
  );
}
