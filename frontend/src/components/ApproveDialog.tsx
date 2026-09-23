import { useMutation } from "@tanstack/react-query";
import { api } from "../api/client";
import type { OrderItem } from "../api/types";
import type { Override } from "./OrderTable";
import { fmtInt } from "../format";
import { downloadCsv } from "../exportCsv";

interface Props {
  items: OrderItem[];
  overrides: Record<string, Override>;
  supplierName: string | null;
  onClose: () => void;
  onDone: (msg: string) => void;
}

export function ApproveDialog({ items, overrides, supplierName, onClose, onDone }: Props) {
  const bySupplier = new Map<string, { name: string; items: OrderItem[] }>();
  for (const i of items) {
    const g = bySupplier.get(i.supplier_id) ?? { name: i.supplier_name, items: [] };
    g.items.push(i);
    bySupplier.set(i.supplier_id, g);
  }
  const edited = items.filter((i) => overrides[i.erp_code]).length;

  const approve = useMutation({
    mutationFn: async () => {
      const ids: string[] = [];
      for (const [supplier_id, g] of bySupplier) {
        const res = await api.approve({
          supplier_id,
          lines: g.items.map((i) => ({
            erp_code: i.erp_code,
            order_qty: overrides[i.erp_code]?.qty ?? i.order_qty,
            reason: overrides[i.erp_code]?.reason,
          })),
        });
        ids.push(res.order_id);
        downloadCsv(g.name, g.items, overrides);
      }
      return ids;
    },
    onSuccess: (ids) => onDone(`Заказ утверждён: ${ids.join(", ")}. CSV для 1С скачан.`),
  });

  return (
    <div className="overlay center" onClick={onClose}>
      <section
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="approve-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="approve-title">Утвердить заказ{supplierName ? ` · ${supplierName}` : ""}</h2>
        <p className="muted">Заказ не уходит поставщику автоматически: после утверждения скачается CSV для загрузки в 1С.</p>
        <ul className="supplier-list">
          {[...bySupplier.values()].map((g) => (
            <li key={g.name}>
              <span className="strong">{g.name}</span>
              <span className="mono">{fmtInt(g.items.length)} поз.</span>
            </li>
          ))}
        </ul>
        {edited > 0 && <div className="banner warn">Правок закупщика: {edited}. Они попадут в заказ вместо расчёта.</div>}
        {approve.isError && <div className="banner bad" role="alert">Не удалось утвердить: {approve.error.message}</div>}
        <div className="row gap end">
          <button className="btn" onClick={onClose} disabled={approve.isPending}>Отмена</button>
          <button className="btn primary" onClick={() => approve.mutate()} disabled={approve.isPending}>
            {approve.isPending ? "Утверждаю…" : "Утвердить и скачать"}
          </button>
        </div>
      </section>
    </div>
  );
}
