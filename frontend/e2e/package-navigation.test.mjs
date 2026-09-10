import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/lib/packageNavigation.ts", import.meta.url), "utf8");
const fixtureModule = { exports: {} };
new Function("exports", ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(fixtureModule.exports);
const { packageNavigation, requiresLocalPackage } = fixtureModule.exports;
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
