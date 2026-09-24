import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { connection } from "next/server";
import { configScript } from "@/lib/public-config";
import { runtimePublicConfig } from "@/lib/runtime-config";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Chaya 02",
  description: "Digital twin platform for physical venues",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Render per request so CHAYA_PUBLIC_* is read from the running container, not frozen at build time
  // (one image is promoted from staging to production; see lib/public-config.ts and DEPLOYMENT.md).
  await connection();
  const { config } = runtimePublicConfig();
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <head>
        {config && <script dangerouslySetInnerHTML={{ __html: configScript(config) }} />}
      </head>
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
