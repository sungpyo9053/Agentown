import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import ts from "typescript";
const fixtureModule = { exports: {} };
new Function("exports", ts.transpileModule(readFileSync(new URL("../src/lib/runInput.ts", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(fixtureModule.exports);
const { buildRunInput, supportsRunForm, runInputDraft } = fixtureModule.exports;
test("returning to the run tab shows the input that would actually be sent", () => {
  assert.deepEqual(runInputDraft('{"text":"hello","rules":["a","b"],"approved":false,"count":0}'),{text:"hello",rules:"a\nb",approved:"false",count:"0"});
  assert.deepEqual(runInputDraft("broken"),{});
});
test("free text, line lists, false and zero preserve their schema types", () => {
  const fields = [{name:"text",type:"string",required:true},{name:"rules",type:"array",itemType:"string",required:true},{name:"approved",type:"boolean",required:true},{name:"count",type:"integer",required:true}];
  assert.equal(supportsRunForm(fields), true);
  assert.deepEqual(buildRunInput(fields, {text:"two\nlines",rules:"no leather\nno laces",approved:"false",count:"0"}), { value:{text:"two\nlines",rules:["no leather","no laces"],approved:false,count:0},errors:[] });
});
test("missing required input and invalid values do not silently become valid", () => {
  assert.equal(buildRunInput([{name:"x",type:"string",required:true}],{}).errors.length,1);
  assert.equal(buildRunInput([{name:"x",type:"integer",required:true}],{x:"1.2"}).errors.length,1);
  assert.equal(buildRunInput([{name:"x",type:"boolean",required:true}],{x:"maybe"}).errors.length,1);
  assert.deepEqual(buildRunInput([{name:"x",type:"string",required:false}],{}),{value:{},errors:[]});
});
test("nested data is not coerced into plain text and constraints stay visible", () => {
  assert.equal(supportsRunForm([{name:"x",type:"object"}]),false);
  assert.equal(supportsRunForm([{name:"x",type:"array",itemType:"object"}]),false);
  assert.equal(buildRunInput([{name:"x",type:"array",minItems:2}],{x:"one"}).errors.length,1);
  assert.equal(buildRunInput([{name:"x",type:"string",enumValues:["allowed"]}],{x:"other"}).errors.length,1);
});
