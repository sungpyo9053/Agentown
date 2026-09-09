import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/lib/clarificationDraft.ts", import.meta.url), "utf8");
const fixtureModule = { exports: {} };
new Function("exports", ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(fixtureModule.exports);
const { readClarificationDraft, clarificationDraftKey, clearClarificationDrafts } = fixtureModule.exports;

test("selected options and free input survive serialization for the same questions", () => {
  const draft = { signature: "questions-v1", selected: { purpose: ["카페용"] }, details: { purpose: "가죽 없이" } };
  assert.deepEqual(readClarificationDraft(JSON.stringify(draft), "questions-v1", ["purpose"]), {
    selected: draft.selected, details: draft.details,
  });
});
test("different conversations and changed questions cannot share a draft", () => {
  assert.notEqual(clarificationDraftKey("one"), clarificationDraftKey("two"));
  assert.deepEqual(readClarificationDraft(JSON.stringify({ signature: "old", details: { q: "stale" } }), "new", ["q"]), { selected: {}, details: {} });
});
test("malformed or unknown fields are ignored and free input stays bounded", () => {
  assert.deepEqual(readClarificationDraft("bad json", "v1", ["q"]), { selected: {}, details: {} });
  const draft = readClarificationDraft(JSON.stringify({ signature: "v1", selected: { q: [1, "valid"] }, details: { q: "x".repeat(600), unknown: "discard" } }), "v1", ["q"]);
  assert.deepEqual(draft.selected, { q: ["valid"] });
  assert.equal(draft.details.q.length, 500);
  assert.equal(draft.details.unknown, undefined);
});
test("explicit logout clears only clarification drafts", () => {
  const values = new Map([[clarificationDraftKey("one"), "draft"], [clarificationDraftKey("two"), "draft"], ["unrelated", "keep"]]);
  clearClarificationDrafts({ get length() { return values.size; }, key: index => [...values.keys()][index] ?? null, removeItem: key => values.delete(key) });
  assert.deepEqual([...values], [["unrelated", "keep"]]);
});
