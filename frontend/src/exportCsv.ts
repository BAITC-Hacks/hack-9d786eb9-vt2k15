import type { OrderItem } from "./api/types";
import type { Override } from "./components/OrderTable";

// Формат для загрузки в 1С: разделитель «;», UTF-8 с BOM, чтобы Excel открыл кириллицу
export function downloadCsv(supplierName: string, items: OrderItem[], overrides: Record<string, Override>) {
  const esc = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`;
  const head = ["Код 1С", "Артикул поставщика", "Наименование", "Ед.", "Количество", "Поставщик", "Обоснование"];
  const rows = items.map((i) => {
    const o = overrides[i.erp_code];
    return [i.erp_code, i.supplier_article, i.name, i.unit, o?.qty ?? i.order_qty, i.supplier_name,
      o ? `Правка закупщика: ${o.reason}` : i.explanation];
  });
  const csv = "﻿" + [head, ...rows].map((r) => r.map(esc).join(";")).join("\r\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  a.download = `order_${supplierName.replace(/\s+/g, "_")}_${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}
