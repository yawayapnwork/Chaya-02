// Browser-side configuration. NEXT_PUBLIC_* variables are inlined at build time and must be
// referenced literally. Nothing secret belongs here.

function required(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} is not set. See .env.example.`);
  return value;
}

export function publicConfig() {
  return {
    apiBaseUrl: required("NEXT_PUBLIC_API_BASE_URL", process.env.NEXT_PUBLIC_API_BASE_URL).replace(/\/+$/, ""),
    oidcIssuer: required("NEXT_PUBLIC_OIDC_ISSUER", process.env.NEXT_PUBLIC_OIDC_ISSUER),
    oidcClientId: required("NEXT_PUBLIC_OIDC_CLIENT_ID", process.env.NEXT_PUBLIC_OIDC_CLIENT_ID),
  };
}
