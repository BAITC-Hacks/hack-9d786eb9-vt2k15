import type { ApiError, ApproveRequest, ApproveResponse, ImportResult, OrdersResponse, ReportKind } from "./types";

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

/**
 * POST /api/imports — multipart/form-data, 12 полей: имя поля = «поставщик.тип отчёта»
 * (systeme.sales_tx, iek.moq, …), значение = файл. XHR, чтобы показывать прогресс загрузки.
 */
export function uploadReports(
  files: { supplierId: string; kind: ReportKind; file: File }[],
  onProgress: (share: number) => void,
): Promise<ImportResult> {
  const form = new FormData();
  for (const f of files) form.append(`${f.supplierId}.${f.kind}`, f.file, f.file.name);
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${BASE}/api/imports`);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      let body: unknown;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        /* не JSON */
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as ImportResult);
      else {
        const err = (body as ApiError | undefined)?.error;
        reject(new HttpError(xhr.status, err?.message ?? `Ошибка ${xhr.status}`, err?.request_id));
      }
    };
    xhr.onerror = () => reject(new HttpError(0, "Нет связи с сервером"));
    xhr.send(form);
  });
}
