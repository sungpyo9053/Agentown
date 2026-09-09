import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import ts from "typescript";
const fixtureModule = { exports: {} };
new Function("exports", ts.transpileModule(readFileSync(new URL("../src/lib/runInput.ts", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(fixtureModule.exports);
const { buildRunInput, supportsRunForm, runInputDraft } = fixtureModule.exports;
test("server null constraints mean no bound, not zero", () => {
  const fields = [{name:"items",type:"array",itemType:"string",required:true,minItems:null,maxItems:null},{name:"count",type:"number",required:true,minimum:null,maximum:null}];
  assert.deepEqual(buildRunInput(fields,{items:"first\nsecond",count:"5"}),{value:{items:["first","second"],count:5},errors:[]});
});
test("partial and invalid drafts survive serialization without enabling execution", () => {
  const fields = [{name:"first",type:"string",required:true},{name:"second",type:"integer",required:true}];
  const partial = buildRunInput(fields,{first:"keep me"});
  assert.deepEqual(runInputDraft(JSON.stringify(partial.value)),{first:"keep me"});
  assert.equal(partial.errors.length,1);
  const invalid = buildRunInput(fields,{first:"keep me",second:"1.2"});
  assert.equal(runInputDraft(JSON.stringify(invalid.value)).second,"1.2");
  assert.equal(buildRunInput(fields,runInputDraft(JSON.stringify(invalid.value))).errors.length,1);
});
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
