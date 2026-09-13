import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/lib/packageNavigation.ts", import.meta.url), "utf8");
const fixtureModule = { exports: {} };
new Function("exports", ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(fixtureModule.exports);
const { packageNavigation, requiresLocalPackage, selectedSessionSearch } = fixtureModule.exports;
test("local-only execution is selected by capability source and leaves hosted packages unchanged", () => {
  assert.equal(requiresLocalPackage(), false);
  assert.equal(requiresLocalPackage([{ source: "SERVER_CATALOG" }]), false);
  assert.equal(requiresLocalPackage([{ source: "DOWNLOADED_PACKAGE" }]), true);
});
const id = "c2a7bf99-d160-4a16-8823-2dcbfb54ad32";
test("downloaded entry chooses the explicit session and output panel", () => {
  assert.deepEqual(packageNavigation(`?session=${id}&panel=output`), { sessionId: id, output: true });
  assert.deepEqual(packageNavigation(`?session=${id}`), { sessionId: id, output: false });
});
test("untrusted navigation cannot choose an arbitrary API path", () => {
  for (const value of ["", "?session=../../admin", "?session=https://evil.example", "?session=bad"]) assert.equal(packageNavigation(value), undefined);
});
test("switching or creating a session cannot reopen the previous package after reload", () => {
  const previous = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  const search = selectedSessionSearch(`?session=${previous}&panel=output&source=package`, id);
  assert.deepEqual(packageNavigation(search), { sessionId: id, output: false });
  assert.equal(new URLSearchParams(search).get("source"), "package");
  assert.equal(packageNavigation(selectedSessionSearch(search)), undefined);
  assert.deepEqual(packageNavigation(selectedSessionSearch(`?session=${id}&panel=output`, id)), { sessionId: id, output: true });
  assert.throws(() => selectedSessionSearch("", "../../admin"));
});

const apiSource = readFileSync(new URL("../src/lib/api.ts", import.meta.url), "utf8");
function downloadApi(fetcher, schedule = setTimeout, clear = clearTimeout) {
  const exported = {};
  new Function("exports", "fetch", "setTimeout", "clearTimeout", ts.transpileModule(apiSource, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText)(exported, fetcher, schedule, clear);
  return exported;
}
test("binary download keeps credentials, avoids redirects and returns received bytes", async () => {
  let signal;
  const { apiBlob } = downloadApi(async (path, options) => {
    assert.equal(path, "/api/package");
    assert.equal(options.credentials, "include");
    assert.equal(options.cache, "no-store");
    assert.equal(options.redirect, "error");
    signal = options.signal;
    return new Response(new Uint8Array([80, 75, 3, 4]), { headers: { "Content-Type": "application/zip" } });
  });
  assert.deepEqual([...new Uint8Array(await (await apiBlob("/package")).arrayBuffer())], [80, 75, 3, 4]);
  assert.equal(signal.aborted, true);
});
test("authentication and permission failures stay distinct and can retry without job creation", async () => {
  for (const status of [401, 403, 500]) {
    let calls = 0;
    const { apiBlob, ApiError } = downloadApi(async () => {
      calls++;
      return new Response("not JSON", { status });
    });
    await assert.rejects(() => apiBlob("/package"), error => error instanceof ApiError && error.status === status && Boolean(error.message));
    assert.equal(calls, 1);
  }
});
test("HTML, JSON, missing content types and empty downloads never become result files", async () => {
  for (const response of [new Response("login", { headers: { "Content-Type": "text/html" } }),
    new Response("{}", { headers: { "Content-Type": "application/json" } }),
    new Response(new Uint8Array([1])), new Response(new Uint8Array(), { headers: { "Content-Type": "application/zip" } })]) {
    const { apiBlob } = downloadApi(async () => response);
    await assert.rejects(() => apiBlob("/package"));
  }
});
test("download size limits cover both declared and streamed bytes", async () => {
  const large = 64 * 1024 * 1024 + 1;
  for (const response of [new Response("small", { headers: { "Content-Type": "application/zip", "Content-Length": String(large) } }),
    new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(large)); controller.close(); } }), { headers: { "Content-Type": "application/zip" } })]) {
    const { apiBlob } = downloadApi(async () => response);
    await assert.rejects(() => apiBlob("/package"), /크기/);
  }
});
test("a stalled download aborts and gives an explicit retry message", async () => {
  const { apiBlob } = downloadApi((_path, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener("abort", () => reject(new Error("aborted")));
  }), callback => { queueMicrotask(callback); return 1; }, () => undefined);
  await assert.rejects(() => apiBlob("/package"), /다시 시도/);
});
