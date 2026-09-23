import type { OrderItem } from "../api/types";
import { fmtInt, fmtMoney, fmtOne, URGENCY } from "../format";

export interface Override {
  qty: number;
  reason: string;
}

interface Props {
  items: OrderItem[];
  overrides: Record<string, Override>;
  onOpen: (code: string) => void;
}

export function OrderTable({ items, overrides, onOpen }: Props) {
  const has = (k: keyof OrderItem) => items.some((i) => i[k] != null);
  const cols = {
    urgency: has("urgency"),
    cls: has("abc_xyz"),
    demand: has("demand_per_month"),
    free: has("free_stock"),
    transit: has("in_transit"),
    cover: has("cover_months"),
    moq: has("moq"),
    sum: has("unit_cost"),
  };

  if (!items.length) return <div className="panel empty-filter">Ничего не найдено — измените фильтр или поиск.</div>;

  return (
    <div className="panel table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th scope="col">Товар</th>
            {cols.urgency && <th scope="col">Срочность</th>}
            {cols.cls && <th scope="col">Класс</th>}
            {cols.demand && <th scope="col" className="num">Спрос, мес</th>}
            {cols.free && <th scope="col" className="num">Свободно</th>}
            {cols.transit && <th scope="col" className="num">В пути</th>}
            {cols.cover && <th scope="col" className="num">Покрытие, мес</th>}
            <th scope="col" className="num">К заказу</th>
            {cols.moq && <th scope="col" className="num">Упаковки</th>}
            {cols.sum && <th scope="col" className="num">Сумма</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((i) => {
            const o = overrides[i.erp_code];
            const qty = o?.qty ?? i.order_qty;
            return (
              <tr key={i.erp_code} onClick={() => onOpen(i.erp_code)} className="clickable">
                <td>
                  <button className="cell-link" onClick={(e) => { e.stopPropagation(); onOpen(i.erp_code); }}>
                    {i.name}
                  </button>
                  <div className="mono muted small">
                    {i.erp_code} · {i.supplier_article}
                  </div>
                </td>
                {cols.urgency && (
                  <td>{i.urgency && <span className={`pill ${URGENCY[i.urgency].tone}`}>{URGENCY[i.urgency].label}</span>}</td>
                )}
                {cols.cls && <td className="mono strong">{i.abc_xyz}</td>}
                {cols.demand && <td className="num mono">{i.demand_per_month != null ? fmtInt(i.demand_per_month) : "—"}</td>}
                {cols.free && <td className="num mono">{i.free_stock != null ? fmtInt(i.free_stock) : "—"}</td>}
                {cols.transit && <td className="num mono muted">{i.in_transit != null ? fmtInt(i.in_transit) : "—"}</td>}
                {cols.cover && <td className="num mono">{i.cover_months != null ? fmtOne(i.cover_months) : "—"}</td>}
                <td className="num mono qty">
                  {fmtInt(qty)} <span className="muted small">{i.unit}</span>
                  {o && <span className="edited" title={`Правка: ${o.reason || "без причины"}`}>изм.</span>}
                </td>
                {cols.moq && <td className="num mono muted">{i.moq ? `${fmtInt(qty / i.moq)} × ${i.moq}` : "—"}</td>}
                {cols.sum && <td className="num mono">{i.unit_cost != null ? fmtMoney(qty * i.unit_cost) : "—"}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
