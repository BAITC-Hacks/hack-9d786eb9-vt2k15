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
        { error: { code: "bad_file", message: "Файл «Товар в пути» не читается: нет колонки «Код 1с»", request_id: "mock-2b81c0" } },
        { status: 422 },
      );
    }
    const form = await request.formData();
    // реальные цифры по выгрузкам SystemElectric от 22.09.2026
    const stats: Record<ReportKind, { rows: number; skus: number; status: "ok" | "warning" }> = {
      sales_tx: { rows: 77309, skus: 565, status: "ok" },
      sales_monthly: { rows: 557, skus: 557, status: "ok" },
      stock_monthly: { rows: 704, skus: 704, status: "warning" },
      seasonality: { rows: 3, skus: 0, status: "ok" },
      in_transit: { rows: 497, skus: 497, status: "warning" },
      moq: { rows: 554, skus: 554, status: "warning" },
    };
    const files = [...form.entries()].map(([kind, f]) => ({
      kind: kind as ReportKind,
      filename: (f as File).name,
      ...stats[kind as ReportKind],
    }));
    const kinds = new Set(files.map((f) => f.kind));
    const issues: ImportResult["issues"] = [];
    if (kinds.has("stock_monthly") && kinds.has("in_transit")) {
      issues.push({ severity: "warning", code: "sku_not_in_plan", message: "SKU есть в остатках, но нет в «Товар в пути» — не попадут в заказ", count: 227 });
      issues.push({ severity: "warning", code: "no_stock_history", message: "SKU из «Товар в пути» нет в остатках — дефицитные месяцы не восстановить", count: 23 });
    }
    if (kinds.has("moq")) issues.push({ severity: "warning", code: "no_moq", message: "SKU без кратности — считаем кратность 1", count: 28 });
    issues.push({ severity: "info", code: "returns", message: "Отрицательные продажи (возвраты) вынесены из спроса", count: 318 });
    issues.push({ severity: "info", code: "partial_month", message: "Последний месяц неполный (до 22.09) — не участвует в прогнозе", count: 1 });
    const res: ImportResult = {
      id: `imp-${Date.now()}`,
      uploaded_at: new Date().toISOString(),
      can_calculate: true,
      files,
      issues,
    };
    return HttpResponse.json(res);
  }),
];
