export class ApiError extends Error {
  constructor(public status: number, message: string, public retryAfterSeconds?: number) { super(message); }
}

function cookie(name: string) {
  if (typeof document === "undefined") return undefined;
  return document.cookie.split("; ").find((row) => row.startsWith(`${name}=`))?.split("=")[1];
}

export async function ensureCsrf() {
  await fetch("/api/auth/csrf", { credentials: "include" });
}

/** Downloads do not start jobs; failures leave the caller's inputs untouched. */
export async function apiBlob(path: string): Promise<Blob> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30_000);
  const limit = 64 * 1024 * 1024;
  try {
    const response = await fetch(`/api${path}`, {
      credentials: "include", cache: "no-store", redirect: "error", signal: controller.signal,
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      const message = response.status === 401 ? "로그인이 필요합니다. 다시 로그인한 뒤 다운로드를 재시도해 주세요."
        : response.status === 403 ? "이 파일을 받을 권한이 없습니다. 에이전트를 만든 계정인지 확인해 주세요."
        : typeof detail?.message === "string" ? detail.message : "파일을 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.";
      throw new ApiError(response.status, message);
    }
    const type = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
    if (!type || type === "text/html" || type === "application/json" || !response.body) {
      throw new Error("파일 대신 다른 응답을 받았습니다. 새로고침 후 다시 시도해 주세요.");
    }
    if (Number(response.headers.get("Content-Length")) > limit) throw new Error("다운로드 가능한 파일 크기를 초과했습니다.");
    const reader = response.body.getReader();
    const chunks: BlobPart[] = [];
    let bytes = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > limit) throw new Error("다운로드 가능한 파일 크기를 초과했습니다.");
        chunks.push(new Uint8Array(value));
      }
    } finally {
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
    }
    if (!bytes) throw new Error("빈 파일이 반환되었습니다. 다시 시도해 주세요.");
    return new Blob(chunks, { type });
  } catch (error) {
    if (controller.signal.aborted) throw new Error("다운로드 응답이 늦어 중단했습니다. 입력은 변경하지 않았습니다. 다시 시도해 주세요.");
    throw error;
  } finally {
    clearTimeout(timeout);
    controller.abort();
  }
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
    const retryAfter = Number(response.headers.get("Retry-After"));
    throw new ApiError(
      response.status,
      response.status === 401 ? "로그인 후 연결할 수 있습니다." : error.message,
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
