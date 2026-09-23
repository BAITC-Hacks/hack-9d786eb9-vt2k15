import type { ReportKind } from "./types";

export interface ReportType {
  kind: ReportKind;
  title: string;
  hint: string;
  required: boolean;
  /** подсказка для автоопределения по имени файла */
  match: RegExp;
}

// Какие отчёты обязательны — согласовать с бэкендом
export const REPORT_TYPES: ReportType[] = [
  { kind: "sales_tx", title: "Динамика продаж", hint: "Расходные накладные: дата, код, количество, склад", required: true, match: /динамик/i },
  { kind: "stock_monthly", title: "Ежемесячные остатки", hint: "Остаток на конец месяца по каждому SKU", required: true, match: /остатк/i },
  { kind: "in_transit", title: "Товар в пути", hint: "Свободный остаток, резерв, товар в пути на дату", required: true, match: /в пути/i },
  { kind: "sales_monthly", title: "Ежемесячные продажи, шт", hint: "Продажи по месяцам и кратность", required: false, match: /кол-?м|продажи в кол/i },
  { kind: "seasonality", title: "Сезонность", hint: "Выручка по месяцам за несколько лет", required: false, match: /сезон/i },
  { kind: "moq", title: "Кратность (MOQ)", hint: "Минимальная партия и кратность упаковки", required: false, match: /moq|кратн/i },
];

export const reportTitle = (k: ReportKind) => REPORT_TYPES.find((r) => r.kind === k)?.title ?? k;

export function guessKind(filename: string): ReportKind | "" {
  return REPORT_TYPES.find((r) => r.match.test(filename))?.kind ?? "";
}
