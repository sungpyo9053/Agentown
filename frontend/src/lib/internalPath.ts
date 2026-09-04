const agentownValidationOrigin = "https://agentown.invalid";

export function internalPathOrFallback(path: string | null | undefined, fallback: string) {
  if (!path?.startsWith("/")) return fallback;

  try {
    const resolved = new URL(path, agentownValidationOrigin);
    return resolved.origin === agentownValidationOrigin ? path : fallback;
  } catch {
    return fallback;
  }
}
