"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { userManager } from "@/lib/auth";

/** Only same-origin paths: a returnTo is never an absolute URL to somewhere else. */
function safeReturnTo(value: unknown): string {
  return typeof value === "string" && value.startsWith("/") && !value.startsWith("//") ? value : "/";
}

export default function AuthCallback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false); // the authorization code can only be redeemed once

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    // In the hidden iframe of a silent sign-in (lib/auth.ts currentUser), hand the answer to the page that asked.
    if (window.self !== window.top) {
      void userManager().signinSilentCallback().catch(() => undefined);
      return;
    }
    userManager()
      .signinRedirectCallback()
      .then((user) => {
        const state = user.state as { returnTo?: string } | undefined;
        router.replace(safeReturnTo(state?.returnTo));
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [router]);

  return (
    <main className="p-8">
      {error ? (
        <div role="alert" className="space-y-3">
          <p className="text-red-700">Sign-in failed: {error}</p>
          <Link className="underline" href="/">Back to the start page</Link>
        </div>
      ) : (
        <p aria-live="polite">Signing you in…</p>
      )}
    </main>
  );
}
