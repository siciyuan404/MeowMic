// MeowMic 更新代理 Worker
//
// 用途:代理 GitHub Releases 资产,绕过国内防火墙导致的 404/超时问题。
// 部署:见同目录 wrangler.toml,执行 `npx wrangler deploy` 即可。
//
// 路由约定:
//   GET /latest.json
//     代理 GitHub Releases 的 latest.json,并把 platforms.*.url 改写为
//     指向 Worker 自身,使后续 .nsis.zip 下载也走 Worker。
//   GET /releases/download/<tag>/<file>
//     流式代理 GitHub Releases 资产(支持大文件,签名文件等)。
//   GET /  或  /health
//     健康检查。

const GITHUB_OWNER = 'siciyuan404';
const GITHUB_REPO = 'MeowMic';
const GITHUB_BASE = `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}`;

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS 预检
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    // 健康检查
    if (path === '/' || path === '/health') {
      return new Response('MeowMic update proxy OK\n', {
        headers: { 'Content-Type': 'text/plain; charset=utf-8', ...corsHeaders() },
      });
    }

    // latest.json:代理 + 改写 url 指向 Worker 自身
    if (path === '/latest.json') {
      try {
        const upstream = await fetch(`${GITHUB_BASE}/releases/latest/download/latest.json`, {
          cf: { cacheTtl: 60, cacheEverything: true },
        });
        if (!upstream.ok) {
          return new Response(`上游 latest.json 状态: ${upstream.status}`, {
            status: 502,
            headers: corsHeaders(),
          });
        }
        const manifest = await upstream.json();
        const workerBase = `${url.protocol}//${url.host}`;
        if (manifest.platforms) {
          for (const key of Object.keys(manifest.platforms)) {
            const p = manifest.platforms[key];
            if (p && p.url) {
              // 原 url: https://github.com/siciyuan404/MeowMic/releases/download/<tag>/<file>
              const m = String(p.url).match(/\/releases\/download\/([^/]+)\/(.+)$/);
              if (m) {
                p.url = `${workerBase}/releases/download/${m[1]}/${m[2]}`;
              }
            }
          }
        }
        return new Response(JSON.stringify(manifest), {
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'public, max-age=60',
            ...corsHeaders(),
          },
        });
      } catch (e) {
        return new Response(`代理 latest.json 失败: ${e.message}`, {
          status: 502,
          headers: corsHeaders(),
        });
      }
    }

    // 资产下载:流式代理 GitHub Releases 资产
    const assetMatch = path.match(/^\/releases\/download\/([^/]+)\/(.+)$/);
    if (assetMatch) {
      const tag = assetMatch[1];
      const file = assetMatch[2];
      const upstreamUrl = `${GITHUB_BASE}/releases/download/${tag}/${file}`;
      try {
        const upstream = await fetch(upstreamUrl, {
          method: 'GET',
          cf: { cacheTtl: 86400, cacheEverything: true },
        });
        const respHeaders = new Headers(upstream.headers);
        respHeaders.set('Access-Control-Allow-Origin', '*');
        // 流式转发,无大小限制
        return new Response(upstream.body, {
          status: upstream.status,
          statusText: upstream.statusText,
          headers: respHeaders,
        });
      } catch (e) {
        return new Response(`代理资产失败: ${e.message}`, {
          status: 502,
          headers: corsHeaders(),
        });
      }
    }

    return new Response('Not Found', { status: 404, headers: corsHeaders() });
  },
};

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': '*',
  };
}
