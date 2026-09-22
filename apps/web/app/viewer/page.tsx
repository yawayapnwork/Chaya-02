import { Suspense } from "react";
import ViewerWorkspace from "@/components/ViewerWorkspace";

export const metadata = { title: "Digital twin viewer · Chaya 02" };

export default function ViewerPage() {
  return (
    <Suspense fallback={<p className="p-8">Loading…</p>}>
      <ViewerWorkspace />
    </Suspense>
  );
}
