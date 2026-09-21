// Server-side configuration. Values come from environment variables only.
export function apiBaseUrl(env: Record<string, string | undefined> = process.env): string {
  const value = env.CHAYA_API_BASE_URL;
  if (!value) {
    throw new Error("CHAYA_API_BASE_URL is not set. See .env.example.");
  }
  return value.replace(/\/+$/, "");
}
