const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Explicit selection replaces stale package deep links; unrelated query values survive. */
export function selectedSessionSearch(search: string, sessionId?: string): string {
  if (sessionId !== undefined && !uuid.test(sessionId)) throw new Error("Invalid session id");
  const params = new URLSearchParams(search);
  if (params.get("session") !== sessionId) params.delete("panel");
  if (sessionId) params.set("session", sessionId);
  else params.delete("session");
  const next = params.toString();
  return next ? `?${next}` : "";
}

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
