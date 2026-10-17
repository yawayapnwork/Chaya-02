"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { currentUser, signOut, userManager } from "@/lib/auth";

const LINKS = [
  { href: "/capture", label: "Capture" },
  { href: "/viewer", label: "Viewer" },
  { href: "/ar", label: "AR" },
  { href: "/ops", label: "Operations" },
  { href: "/status", label: "Status" },
];

/**
 * The app's one navigation bar: every page, one fixed height (2.75rem, which the full-screen viewer subtracts), its links
 * scrolling sideways on a narrow screen instead of widening the page. "Sign out" appears only while someone is signed in,
 * and ends the Keycloak session (lib/auth.ts signOut), not only this tab's tokens.
 */
export default function AppNav() {
  const pathname = usePathname();
  const [signedIn, setSignedIn] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  // Not on the sign-in callback: in the silent sign-in's hidden iframe it would start a sign-in of its own.
  const onCallback = pathname.startsWith("/auth/");

  useEffect(() => {
    if (onCallback) return;
    let cancelled = false;
    const mgr = userManager();
    const loaded = () => { if (!cancelled) setSignedIn(true); };
    const unloaded = () => { if (!cancelled) setSignedIn(false); };
    currentUser().then((u) => !cancelled && setSignedIn(!!u && !u.expired), () => undefined);
    mgr.events.addUserLoaded(loaded);
    mgr.events.addUserUnloaded(unloaded);
    mgr.events.addUserSignedOut(unloaded);
    return () => {
      cancelled = true;
      mgr.events.removeUserLoaded(loaded);
      mgr.events.removeUserUnloaded(unloaded);
      mgr.events.removeUserSignedOut(unloaded);
    };
  }, [onCallback]);

  if (onCallback) return null;
  return (
    <nav aria-label="Main" className="flex h-11 shrink-0 items-center gap-3 border-b bg-white px-3 text-sm">
      <Link href="/" className="shrink-0 font-semibold" aria-current={pathname === "/" ? "page" : undefined}>Chaya 02</Link>
      <ul className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto">
        {LINKS.map((l) => {
          const current = pathname === l.href || pathname.startsWith(`${l.href}/`);
          return (
            <li key={l.href} className="shrink-0">
              <Link href={l.href} aria-current={current ? "page" : undefined}
                className={`inline-flex min-h-9 items-center rounded px-2 ${current ? "bg-zinc-900 text-white" : "hover:bg-zinc-100"}`}>
                {l.label}
              </Link>
            </li>
          );
        })}
      </ul>
      {signedIn && (
        <button type="button" className="min-h-9 shrink-0 rounded border px-2 disabled:opacity-50" disabled={signingOut}
          onClick={() => { setSigningOut(true); signOut().catch(() => setSigningOut(false)); }}>
          {signingOut ? "Signing out…" : "Sign out"}
        </button>
      )}
    </nav>
  );
}
