import type { ApiError, ApproveRequest, ApproveResponse, OrdersResponse } from "./types";

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
