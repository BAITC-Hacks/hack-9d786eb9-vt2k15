import { backendField } from "./reports";
import type { ApiError, ApproveRequest, ApproveResponse, OrdersResponse, ReportKind, UploadResponse } from "./types";

const BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? "";

export class HttpError extends Error {
  status: number;
  requestId?: string;
  constructor(status: number, message: string, requestId?: string) {
    super(message);
    this.status = status;
    this.requestId = requestId;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    let body: ApiError | undefined;
    try {
      body = await res.json();
    } catch {
      /* не JSON */
    }
    throw new HttpError(res.status, body?.error.message ?? `Ошибка ${res.status}`, body?.error.request_id);
  }
  return res.json() as Promise<T>;
}

export const api = {
  orders: () => request<OrdersResponse>("/api/orders"),
  approve: (body: ApproveRequest) =>
    request<ApproveResponse>("/api/orders/approve", { method: "POST", body: JSON.stringify(body) }),
};

/** RFC 7807 problem+json — так backend (Spring) отдаёт ошибки. */
interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
}

/**
 * POST /api/excel — multipart/form-data. Имя поля — как ждёт backend: `iekMoq`, `systemeSalesDynamics`, …
 * Каждый поставщик отправляется целиком (6 отчётов); можно один или обоих. XHR — ради прогресса загрузки.
 */
export function uploadReports(
  files: { supplierId: string; kind: ReportKind; file: File }[],
  onProgress: (share: number) => void,
): Promise<UploadResponse> {
  const form = new FormData();
  for (const f of files) form.append(backendField(f.supplierId, f.kind), f.file, f.file.name);
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${BASE}/api/excel`);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      let body: unknown;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        /* не JSON */
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as UploadResponse);
      else {
        // backend: problem+json {detail, title}; на всякий случай поддержим и {error:{message}}
        const problem = body as ProblemDetail & Partial<ApiError>;
        const message = problem?.detail ?? problem?.error?.message ?? problem?.title ?? `Ошибка ${xhr.status}`;
        reject(new HttpError(xhr.status, message, problem?.error?.request_id));
      }
    };
    xhr.onerror = () => reject(new HttpError(0, "Нет связи с сервером"));
    xhr.send(form);
  });
}
