import { http, HttpResponse, delay } from "msw";
import orders from "./orders.json";
import type { ApproveRequest, ImportResult, OrdersResponse, ReportKind } from "../api/types";

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


  http.post("*/api/imports", async ({ request }) => {
    await delay(scenario() === "slow" ? 3000 : 900);
    if (scenario() === "upload-error") {
      return HttpResponse.json(
        { error: { code: "bad_file", message: "IEK · «Товар в пути»: не найдена колонка «Код 1с»", request_id: "mock-2b81c0" } },
        { status: 422 },
      );
    }
    const form = await request.formData();
    // реальные цифры по выгрузкам от 22.09.2026
    type Stat = { rows: number; skus: number; status: "ok" | "warning" };
    const stats: Record<string, Partial<Record<ReportKind, Stat>>> = {
      systeme: {
        sales_tx: { rows: 77309, skus: 565, status: "ok" },
        sales_monthly: { rows: 557, skus: 557, status: "ok" },
        stock_monthly: { rows: 704, skus: 704, status: "warning" },
        seasonality: { rows: 3, skus: 0, status: "ok" },
        in_transit: { rows: 497, skus: 497, status: "warning" },
        moq: { rows: 554, skus: 554, status: "warning" },
      },
      iek: {
        sales_tx: { rows: 171604, skus: 2151, status: "ok" },
        sales_monthly: { rows: 2463, skus: 2463, status: "ok" },
        stock_monthly: { rows: 2853, skus: 2853, status: "ok" },
        seasonality: { rows: 3, skus: 0, status: "ok" },
        in_transit: { rows: 2641, skus: 2616, status: "ok" },
        moq: { rows: 1937, skus: 1937, status: "ok" },
      },
    };
    const files = [...form.entries()].map(([key, f]) => {
      const [supplier_id, kind] = key.split(".") as [string, ReportKind];
      const st = stats[supplier_id]?.[kind] ?? { rows: 0, skus: 0, status: "warning" as const };
      return { supplier_id, kind, filename: (f as File).name, ...st };
    });
    const issues: ImportResult["issues"] = [
      { supplier_id: "systeme", severity: "warning", code: "sku_not_in_plan", message: "SKU есть в остатках, но нет в «Товар в пути» — не попадут в заказ", count: 227 },
      { supplier_id: "systeme", severity: "warning", code: "no_moq", message: "SKU без кратности — считаем кратность 1", count: 28 },
      { supplier_id: "systeme", severity: "info", code: "returns", message: "Отрицательные продажи (возвраты) вынесены из спроса", count: 318 },
      { severity: "info", code: "partial_month", message: "Сентябрь 2026 неполный (до 22.09) — не участвует в прогнозе", count: 1 },
    ];
    const res: ImportResult = { id: `imp-${Date.now()}`, uploaded_at: new Date().toISOString(), can_calculate: true, files, issues };
    return HttpResponse.json(res);
  }),
];
