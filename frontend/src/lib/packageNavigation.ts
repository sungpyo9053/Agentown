const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function packageNavigation(search: string): { sessionId: string; output: boolean } | undefined {
  const params = new URLSearchParams(search);
  const sessionId = params.get("session");
  if (!sessionId || !uuid.test(sessionId)) return undefined;
  return { sessionId, output: params.get("panel") === "output" };
}
