import { http, HttpResponse, delay } from "msw";
import orders from "./orders.json";
import type { ApproveRequest, OrdersResponse } from "../api/types";

// Сценарий мока выбирается параметром страницы: ?mock=error | empty | slow | base
const scenario = () => new URLSearchParams(window.location.search).get("mock");

/** Только поля, которые бэкенд уже отдаёт (docs/front data.txt). */
const baseOnly = (data: OrdersResponse): OrdersResponse => ({
  items: data.items.map(
    ({ erp_code, supplier_id, supplier_name, supplier_article, name, unit, order_qty, explanation }) => ({
      erp_code, supplier_id, supplier_name, supplier_article, name, unit, order_qty, explanation,
    }),
  ),
});

export const handlers = [
  http.get("*/api/orders", async () => {
    const s = scenario();
    await delay(s === "slow" ? 4000 : 500);
    if (s === "error") {
      return HttpResponse.json(
        { error: { code: "internal", message: "Сервер не ответил вовремя", request_id: "mock-7f3a91" } },
        { status: 503 },
      );
    }
    if (s === "empty") return HttpResponse.json({ items: [] });
    const data = orders as OrdersResponse;
    return HttpResponse.json(s === "base" ? baseOnly(data) : data);
  }),

  http.post("*/api/orders/approve", async ({ request }) => {
    const body = (await request.json()) as ApproveRequest;
    await delay(700);
    return HttpResponse.json({
      order_id: `SE-${new Date().toISOString().slice(0, 10)}-${body.lines.length}`,
      approved_at: new Date().toISOString(),
    });
  }),
];
