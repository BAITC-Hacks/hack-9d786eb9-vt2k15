import { http, HttpResponse, delay } from "msw";
import orders from "./orders.json";
import type { ApproveRequest, OrdersResponse, UploadResponse } from "../api/types";

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


  http.post("*/api/excel", async ({ request }) => {
    await delay(scenario() === "slow" ? 3000 : 900);
    if (scenario() === "upload-error") {
      // backend отдаёт ошибки как problem+json (spring.mvc.problemdetails)
      return HttpResponse.json(
        { title: "Bad Request", status: 400, detail: "systemeIncomingShipments: не найдена колонка «Код 1с»." },
        { status: 400 },
      );
    }
    const form = await request.formData();
    // число разобранных записей по выгрузкам от 22.09.2026
    const recordCountsByField: Record<string, number> = {
      iekMoq: 1937, iekSalesDynamics: 171604, iekMonthlyStocks: 2853,
      iekMonthlySales: 2463, iekIncomingShipments: 2641, iekSeasonality: 12,
      systemeMoq: 554, systemeSalesDynamics: 77309, systemeMonthlyStocks: 704,
      systemeMonthlySales: 557, systemeIncomingShipments: 497, systemeSeasonality: 12,
    };
    const fields = [...form.keys()];
    const recordCounts: Record<string, number> = {};
    for (const field of fields) recordCounts[field] = recordCountsByField[field] ?? 0;
    const hasSysteme = fields.some((f) => f.startsWith("systeme"));
    const hasIek = fields.some((f) => f.startsWith("iek"));
    // замечания к ячейкам — только по пришедшим поставщикам
    const allIssues: UploadResponse["issues"] = [
      { fileName: "Товар в пути_SystemElectric на 22.09.2026.xlsx", sheet: "Лист1", cell: "AF12", message: "Не число в «средние продажи» — сохранено null", rawValue: "н/д" },
      { fileName: "MOQ SystemElectric.xlsx", sheet: "MOQ", cell: "D57", message: "Пустая кратность — при расчёте примем 1", rawValue: null },
      { fileName: "Сезонность ИЭК.xlsx", sheet: "2026", cell: "M3", message: "Сентябрь 2026 неполный — коэффициент предварительный", rawValue: "0.7" },
    ];
    const issues = allIssues.filter(
      (i) => (hasSysteme && /systemelectric|systeme/i.test(i.fileName)) || (hasIek && /иэк|iek/i.test(i.fileName)),
    );
    const res: UploadResponse = {
      iekFiles: hasIek ? 6 : 0,
      systemeFiles: hasSysteme ? 6 : 0,
      totalFiles: fields.length,
      recordCounts,
      issues,
    };
    return HttpResponse.json(res);
  }),
];
