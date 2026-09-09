export type ClarificationDraft = { selected: Record<string, string[]>; details: Record<string, string> };

export function clarificationDraftKey(conversationId: string) {
  return `agentown.clarification-draft.v1:${conversationId}`;
}

export function clearClarificationDrafts(storage: Pick<Storage, "length" | "key" | "removeItem">) {
  const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index));
  keys.forEach(key => { if (key?.startsWith("agentown.clarification-draft.v1:")) storage.removeItem(key); });
}

export function readClarificationDraft(raw: string | null, signature: string, questionIds: string[]): ClarificationDraft {
  const empty = { selected: {}, details: {} };
  if (!raw) return empty;
  try {
    const value = JSON.parse(raw);
    if (!value || value.signature !== signature) return empty;
    return {
      selected: Object.fromEntries(questionIds.map(id => [id, Array.isArray(value.selected?.[id])
        ? value.selected[id].filter((item: unknown) => typeof item === "string").slice(0, 30) : []])),
      details: Object.fromEntries(questionIds.map(id => [id, typeof value.details?.[id] === "string"
        ? value.details[id].slice(0, 500) : ""])),
    };
  } catch { return empty; }
}
