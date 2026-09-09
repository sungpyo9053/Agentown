import assert from "node:assert/strict";
import http from "node:http";
import { parse } from "node:url";
import { test } from "node:test";
import { createRequire } from "node:module";
import config from "../next.config.mjs";

const require = createRequire(import.meta.url);
const { proxyRequest } = require("next/dist/server/lib/router-utils/proxy-request.js");
const listen = server => new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const close = server => new Promise(resolve => server.close(resolve));

test("runtime response survives the previous 30 second rewrite deadline", { timeout: 45_000 }, async () => {
  assert.equal(config.experimental.proxyTimeout, 600_000);
  const upstream = http.createServer((_req, res) => {
    setTimeout(() => { res.setHeader("Content-Type", "application/json"); res.end('{"status":"SUCCEEDED"}'); }, 31_000);
  });
  await listen(upstream);
  const proxy = http.createServer((req, res) => {
    proxyRequest(req, res, parse(`http://127.0.0.1:${upstream.address().port}/execute`, true), undefined, undefined, config.experimental.proxyTimeout)
      .catch(error => { res.statusCode = 502; res.end(error.message); });
  });
  await listen(proxy);
  try {
    const response = await fetch(`http://127.0.0.1:${proxy.address().port}/execute`, { method: "POST" });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "SUCCEEDED" });
  } finally {
    await close(proxy);
    await close(upstream);
  }
});
