const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Execution location comes from registered capabilities, not the user's topic. */
export function requiresLocalPackage(bindings: ReadonlyArray<{ source?: string }> = []): boolean {
  return bindings.some(binding => binding.source === "DOWNLOADED_PACKAGE");
}

export function packageNavigation(search: string): { sessionId: string; output: boolean } | undefined {
  const params = new URLSearchParams(search);
  const sessionId = params.get("session");
  if (!sessionId || !uuid.test(sessionId)) return undefined;
  return { sessionId, output: params.get("panel") === "output" };
}
