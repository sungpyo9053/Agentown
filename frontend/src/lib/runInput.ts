export type RunInputField = { name: string; type: string; required: boolean; description: string; itemType?: string; enumValues?: string[]; minItems?: number; maxItems?: number; minLength?: number; minimum?: number; maximum?: number };

export function supportsRunForm(fields: RunInputField[]) {
  return fields.length > 0 && fields.every(field => ["string", "number", "integer", "boolean"].includes(field.type) || (field.type === "array" && field.itemType === "string"));
}

export function runInputDraft(input: string): Record<string, string> {
  try {
    const parsed: unknown = JSON.parse(input);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return Object.fromEntries(Object.entries(parsed).map(([key, value]) => [key,
      Array.isArray(value) && value.every(item => typeof item === "string") ? value.join("\n")
        : ["string", "number", "boolean"].includes(typeof value) ? String(value) : "",
    ]));
  } catch { return {}; }
}

export function buildRunInput(fields: RunInputField[], draft: Record<string, string>) {
  const entries: Array<[string, unknown]> = [];
  const errors: string[] = [];
  for (const field of fields) {
    const raw = draft[field.name] ?? "";
    const label = field.description || field.name;
    if (!raw.trim()) { if (field.required) errors.push(`${label}: 입력해 주세요.`); continue; }
    let value: unknown = raw;
    if (field.type === "array") {
      const items = raw.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
      value = items;
      if (field.minItems != null && items.length < field.minItems) errors.push(`${label}: ${field.minItems}개 이상 입력해 주세요.`);
      if (field.maxItems != null && items.length > field.maxItems) errors.push(`${label}: ${field.maxItems}개 이하로 입력해 주세요.`);
    } else if (field.type === "boolean") {
      if (!["true", "false"].includes(raw)) errors.push(`${label}: 예 또는 아니요를 선택해 주세요.`);
      value = raw === "true";
    } else if (["number", "integer"].includes(field.type)) {
      value = Number(raw);
      if (!Number.isFinite(value) || (field.type === "integer" && !Number.isInteger(value))) {
        errors.push(`${label}: 올바른 숫자를 입력해 주세요.`);
        value = raw;
      }
      else if ((field.minimum != null && Number(value) < field.minimum) || (field.maximum != null && Number(value) > field.maximum)) errors.push(`${label}: 허용 범위를 확인해 주세요.`);
    } else {
      if (field.minLength != null && raw.length < field.minLength) errors.push(`${label}: ${field.minLength}자 이상 입력해 주세요.`);
      if (field.enumValues?.length && !field.enumValues.includes(raw)) errors.push(`${label}: 제공된 선택지에서 골라 주세요.`);
    }
    entries.push([field.name, value]);
  }
  return { value: Object.fromEntries(entries), errors };
}
