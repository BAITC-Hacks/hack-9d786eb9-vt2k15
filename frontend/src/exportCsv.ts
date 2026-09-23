import type { OrderItem } from "./api/types";
import type { Override } from "./components/OrderTable";

const HEAD = ["Код 1С", "Артикул поставщика", "Наименование", "Ед.", "Количество", "Поставщик", "Обоснование"];

function rowsFor(items: OrderItem[], overrides: Record<string, Override>) {
  return items.map((i) => {
    const o = overrides[i.erp_code];
    return [
      i.erp_code,
      i.supplier_article,
      i.name,
      i.unit,
      o?.qty ?? i.order_qty,
      i.supplier_name,
      o ? `Правка закупщика: ${o.reason}` : i.explanation,
    ];
  });
}

const fileBase = (supplierName: string) =>
  `order_${supplierName.replace(/\s+/g, "_")}_${new Date().toISOString().slice(0, 10)}`;

// Утверждённый заказ выгружается в .xlsx для загрузки в 1С. xlsx грузим динамически — не тянем в основной бандл.
export async function downloadXlsx(supplierName: string, items: OrderItem[], overrides: Record<string, Override>) {
  const XLSX = await import("xlsx");
  const ws = XLSX.utils.aoa_to_sheet([HEAD, ...rowsFor(items, overrides)]);
  ws["!cols"] = [{ wch: 16 }, { wch: 22 }, { wch: 42 }, { wch: 6 }, { wch: 12 }, { wch: 18 }, { wch: 54 }];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, "Заказ");
  XLSX.writeFile(wb, `${fileBase(supplierName)}.xlsx`);
}

// Формат для загрузки в 1С: разделитель «;», UTF-8 с BOM, чтобы Excel открыл кириллицу
export function downloadCsv(supplierName: string, items: OrderItem[], overrides: Record<string, Override>) {
  const esc = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`;
  const csv = "﻿" + [HEAD, ...rowsFor(items, overrides)].map((r) => r.map(esc).join(";")).join("\r\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  a.download = `${fileBase(supplierName)}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}
