import type { ReportKind } from "./types";

export interface Supplier {
  id: string;
  name: string;
  /** признак поставщика в имени файла */
  match: RegExp;
}

export interface ReportType {
  kind: ReportKind;
  title: string;
  hint: string;
  /** подсказка для автоопределения по имени файла */
  match: RegExp;
}

// 6 отчётов на поставщика. Достаточно загрузить одного поставщика целиком (6 файлов) или обоих (12).
export const SUPPLIERS: Supplier[] = [
  { id: "systeme", name: "Systeme Electric", match: /syst?e?me\s*_?electric|syseme|systemelectric/i },
  { id: "iek", name: "IEK", match: /иэк|iek/i },
];

export const REPORT_TYPES: ReportType[] = [
  { kind: "sales_tx", title: "Динамика продаж", hint: "Расходные накладные: дата, код, количество, склад", match: /динамик/i },
  { kind: "sales_monthly", title: "Ежемесячные продажи, шт", hint: "Продажи по месяцам в штуках", match: /кол-?м|количествен/i },
  { kind: "stock_monthly", title: "Ежемесячные остатки", hint: "Остаток на конец месяца по каждому SKU", match: /остатк/i },
  { kind: "in_transit", title: "Товар в пути", hint: "Поставки в пути на дату выгрузки", match: /в\s*пути|путь/i },
  { kind: "seasonality", title: "Сезонность", hint: "Выручка по месяцам за несколько лет", match: /сезон/i },
  { kind: "moq", title: "Кратность (MOQ)", hint: "Минимальная партия и кратность упаковки", match: /moq|кратн/i },
];

export const TOTAL_FILES = SUPPLIERS.length * REPORT_TYPES.length;

export const slotKey = (supplierId: string, kind: ReportKind) => `${supplierId}.${kind}`;
export const reportTitle = (k: ReportKind) => REPORT_TYPES.find((r) => r.kind === k)?.title ?? k;
export const supplierName = (id: string) => SUPPLIERS.find((s) => s.id === id)?.name ?? id;

// Имя поля multipart для backend `POST /api/excel`: `iekMoq`, `systemeSalesDynamics`, …
const KIND_FIELD: Record<ReportKind, string> = {
  sales_tx: "SalesDynamics",
  sales_monthly: "MonthlySales",
  stock_monthly: "MonthlyStocks",
  in_transit: "IncomingShipments",
  seasonality: "Seasonality",
  moq: "Moq",
};

export const backendField = (supplierId: string, kind: ReportKind) => `${supplierId}${KIND_FIELD[kind]}`;

/** Обратный разбор имени поля backend в пару «поставщик + тип отчёта». */
export function parseBackendField(field: string): { supplierId: string; kind: ReportKind } | null {
  const supplier = SUPPLIERS.find((s) => field.startsWith(s.id));
  if (!supplier) return null;
  const suffix = field.slice(supplier.id.length);
  const entry = (Object.entries(KIND_FIELD) as [ReportKind, string][]).find(([, v]) => v === suffix);
  return entry ? { supplierId: supplier.id, kind: entry[0] } : null;
}

export function guessKind(filename: string): ReportKind | null {
  return REPORT_TYPES.find((r) => r.match.test(filename))?.kind ?? null;
}

export function guessSupplier(filename: string): string | null {
  return SUPPLIERS.find((s) => s.match.test(filename))?.id ?? null;
}
