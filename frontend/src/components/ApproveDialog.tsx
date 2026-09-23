import type { OrderItem } from "../api/types";
import type { Override } from "./OrderTable";
import { fmtInt } from "../format";
import { downloadXlsx } from "../exportCsv";

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

  // Утверждение формирует Excel-файл заказа локально (по одному на поставщика) — без обращения к backend.
  async function approve() {
    const names: string[] = [];
    for (const [, g] of bySupplier) {
      await downloadXlsx(g.name, g.items, overrides);
      names.push(g.name);
    }
    onDone(
      names.length > 1
        ? `Excel-файлы заказа сформированы: ${names.join(", ")}.`
        : `Excel-файл заказа сформирован: ${names[0]}.`,
    );
  }

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
        <p className="muted">Заказ не уходит поставщику автоматически: после утверждения сформируется Excel-файл для загрузки в 1С.</p>
        <ul className="supplier-list">
          {[...bySupplier.values()].map((g) => (
            <li key={g.name}>
              <span className="strong">{g.name}</span>
              <span className="mono">{fmtInt(g.items.length)} поз.</span>
            </li>
          ))}
        </ul>
        {edited > 0 && <div className="banner warn">Правок закупщика: {edited}. Они попадут в заказ вместо расчёта.</div>}
        <div className="row gap end">
          <button className="btn" onClick={onClose}>Отмена</button>
          <button className="btn primary" onClick={approve}>Сформировать Excel</button>
        </div>
      </section>
    </div>
  );
}
