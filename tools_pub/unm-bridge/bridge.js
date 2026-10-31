'use strict';
/**
 * UnblockNeteaseMusic matching bridge for UniMusic.
 *
 * Upstream (`src/provider/match.js`) is a library, not a service: `src/app.js` starts the MITM
 * proxy, which only rewrites `/api/song/enhance/player/url` responses. This file exposes the very
 * same matching entry point as two read-only HTTP endpoints so the Android client can ask for one
 * song id and receive one audio address.
 *
 * It must run *inside* the UnblockNeteaseMusic checkout, because that project installs its
 * dependencies through Yarn PnP (`--require ./.pnp.cjs`). `run.ps1` copies this file there for you.
 *
 * Contract:
 *   GET /health                          -> {"ok":true,"version":"<unm version>","sources":[...]}
 *   GET /match?id=<neteaseId>[&sources=] -> {"ok":true,"id","source","url","size","br","md5"}
 *                                        -> {"ok":false,"reason":"<reason>"}   (HTTP 200)
 *
 * Nothing here is forwarded to the caller except what upstream already returns: no cookies, no
 * request headers, no NetEase credentials.
 */

const http = require('node:http');
const os = require('node:os');

const ALLOWED_SOURCES = ['pyncmd', 'bodian', 'kuwo'];
// Upstream races providers with `Promise.any`, so the fastest answer wins: `kuwo` (128 kb/s) beats the
// 320 kb/s providers every time. Tiers keep the quality order instead — the first tier answers in
// ~0.2 s via `bodian`, and `kuwo` is only reached when both 320 kb/s providers refuse the id.
const TIERS = (process.env.UNM_BRIDGE_TIERS || 'pyncmd,bodian|kuwo')
  .split('|')
  .map((tier) =>
    tier
      .split(',')
      .map((name) => name.trim())
      .filter((name) => ALLOWED_SOURCES.includes(name))
  )
  .filter((tier) => tier.length > 0);
const SOURCES = TIERS.flat();
const PORT = Number(process.env.UNM_BRIDGE_PORT || 8900);
const BIND = process.env.UNM_BRIDGE_BIND || '0.0.0.0';
const TOKEN = process.env.UNM_BRIDGE_TOKEN || '';
const MATCH_TIMEOUT_MS = Number(process.env.UNM_BRIDGE_TIMEOUT_MS || 15000);

global.endpoint = 'https://music.163.com';
global.hosts = {};
global.cnrelay = false;
global.source = SOURCES;

const packageInfo = require('./package.json');
const match = require('./src/provider/match.js');

function sendJson(response, statusCode, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  response.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
  });
  response.end(body);
}

function describeError(error) {
  const message = String((error && error.message) || error || 'unknown');
  if (/not available|SongNotAvailable/i.test(message)) return 'not_found';
  if (/timeout|timed out/i.test(message)) return 'upstream_timeout';
  if (/RequestFailed|status/i.test(message)) return 'upstream_rejected';
  return 'match_failed';
}

function withTimeout(promise, timeoutMs) {
  let timer;
  const guard = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('bridge timeout')), timeoutMs);
    if (typeof timer.unref === 'function') timer.unref();
  });
  return Promise.race([promise, guard]).finally(() => clearTimeout(timer));
}

async function handleMatch(url, response) {
  const id = url.searchParams.get('id') || '';
  if (!/^[1-9]\d{0,18}$/.test(id)) {
    sendJson(response, 400, { ok: false, reason: 'bad_id' });
    return;
  }
  const requested = (url.searchParams.get('sources') || '')
    .split(',')
    .map((name) => name.trim())
    .filter((name) => ALLOWED_SOURCES.includes(name));
  const tiers = requested.length > 0 ? [requested] : TIERS;
  let lastReason = 'not_found';
  for (const tier of tiers) {
    try {
      const resolved = await withTimeout(match(id, tier), MATCH_TIMEOUT_MS);
      if (!resolved || !resolved.url) continue;
      sendJson(response, 200, {
        ok: true,
        id,
        source: resolved.source || tier[0],
        url: resolved.url,
        size: resolved.size || 0,
        br: resolved.br || 0,
        md5: resolved.md5 || null,
      });
      return;
    } catch (error) {
      lastReason = describeError(error);
    }
  }
  sendJson(response, 200, { ok: false, reason: lastReason });
}

const server = http.createServer((request, response) => {
  let url;
  try {
    url = new URL(request.url, 'http://bridge.invalid');
  } catch {
    sendJson(response, 400, { ok: false, reason: 'bad_request' });
    return;
  }
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    sendJson(response, 405, { ok: false, reason: 'method_not_allowed' });
    return;
  }
  if (TOKEN && request.headers['x-unm-token'] !== TOKEN) {
    sendJson(response, 401, { ok: false, reason: 'unauthorized' });
    return;
  }
  if (url.pathname === '/health') {
    sendJson(response, 200, { ok: true, version: packageInfo.version, sources: SOURCES, tiers: TIERS });
    return;
  }
  if (url.pathname === '/match') {
    handleMatch(url, response).catch(() => sendJson(response, 500, { ok: false, reason: 'match_failed' }));
    return;
  }
  sendJson(response, 404, { ok: false, reason: 'not_found' });
});

server.listen(PORT, BIND, () => {
  const addresses = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) addresses.push(entry.address);
    }
  }
  console.log(`[unm-bridge] ready on http://${BIND}:${PORT} (unm ${packageInfo.version}, tiers ${TIERS.map((tier) => tier.join('+')).join(' -> ')})`);
  for (const address of addresses) console.log(`[unm-bridge] reachable at http://${address}:${PORT}`);
  if (TOKEN) console.log('[unm-bridge] shared token required via header x-unm-token');
});
