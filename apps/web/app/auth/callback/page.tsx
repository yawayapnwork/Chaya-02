"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { userManager } from "@/lib/auth";

export default function AuthCallback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false); // the authorization code can only be redeemed once

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    userManager()
      .signinRedirectCallback()
      .then((user) => {
        const state = user.state as { returnTo?: string } | undefined;
        router.replace(state?.returnTo ?? "/capture");
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [router]);

  return (
    <main className="p-8">
      {error ? <p role="alert" className="text-red-700">Sign-in failed: {error}</p> : <p>Signing you in…</p>}
    </main>
  );
}
