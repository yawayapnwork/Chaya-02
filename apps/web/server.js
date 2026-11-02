const fs = require('fs');
const path = require('path');

function loadEnvFile(filePath) {
  if (fs.existsSync(filePath)) {
    const content = fs.readFileSync(filePath, 'utf-8');
    for (const rawLine of content.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const eqIdx = line.indexOf('=');
      if (eqIdx !== -1) {
        const key = line.slice(0, eqIdx).trim();
        const val = line.slice(eqIdx + 1).trim().replace(/^['"]|['"]$/g, '');
        if (!process.env[key]) {
          process.env[key] = val;
        }
      }
    }
  }
}

loadEnvFile(path.join(__dirname, '.env.local'));
loadEnvFile(path.join(__dirname, '..', '..', '.env'));
loadEnvFile(path.join(__dirname, '.env'));

// Fallbacks for local dev
if (!process.env.CHAYA_API_BASE_URL) {
  process.env.CHAYA_API_BASE_URL = 'http://localhost:18080';
}
if (!process.env.CHAYA_PUBLIC_API_BASE_URL) {
  process.env.CHAYA_PUBLIC_API_BASE_URL = 'http://localhost:18080';
}
if (!process.env.CHAYA_PUBLIC_OIDC_ISSUER) {
  process.env.CHAYA_PUBLIC_OIDC_ISSUER = 'http://localhost:8081/realms/chaya';
}
if (!process.env.CHAYA_PUBLIC_OIDC_CLIENT_ID) {
  process.env.CHAYA_PUBLIC_OIDC_CLIENT_ID = 'chaya-web';
}

require('./.next/standalone/server.js');
