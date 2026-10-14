import { getGatewayTokens } from './server.js';

try {
  const token = getGatewayTokens()[0];
  const response = await fetch(`http://127.0.0.1:${process.env.PORT || 8787}/health`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: AbortSignal.timeout(4000),
  });
  process.exitCode = response.ok && (await response.json()).ok ? 0 : 1;
} catch {
  process.exitCode = 1;
}
