export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

function cookie(name: string) {
  if (typeof document === "undefined") return undefined;
  return document.cookie.split("; ").find((row) => row.startsWith(`${name}=`))?.split("=")[1];
}

export async function ensureCsrf() {
  await fetch("/api/auth/csrf", { credentials: "include" });
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = init.method?.toUpperCase() ?? "GET";
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && !cookie("XSRF-TOKEN")) await ensureCsrf();
  const response = await fetch(`/api${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(cookie("XSRF-TOKEN") ? { "X-XSRF-TOKEN": decodeURIComponent(cookie("XSRF-TOKEN")!) } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: "요청을 처리하지 못했습니다." }));
    throw new ApiError(response.status, response.status === 401 ? "로그인 후 연결할 수 있습니다." : error.message);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
