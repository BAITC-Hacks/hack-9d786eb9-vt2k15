import { useEffect, useState } from "react";
import type { OrderItem } from "../api/types";
import type { Override } from "./OrderTable";
import { fmtInt, fmtOne, URGENCY } from "../format";

interface Props {
  item: OrderItem;
  override?: Override;
  onClose: () => void;
  onSave: (o: Override) => void;
  onReset: () => void;
}

export function ItemDrawer({ item, override, onClose, onSave, onReset }: Props) {
  const [qty, setQty] = useState(String(override?.qty ?? item.order_qty));
  const [reason, setReason] = useState(override?.reason ?? "");

  useEffect(() => {
    setQty(String(override?.qty ?? item.order_qty));
    setReason(override?.reason ?? "");
  }, [item.erp_code, override, item.order_qty]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const n = Number(qty);
  const moq = item.moq;
  const error =
    qty.trim() === "" || !Number.isFinite(n) || n < 0 || !Number.isInteger(n)
      ? "Введите целое число от 0"
      : moq && n % moq !== 0
        ? `Количество должно быть кратно ${moq}`
        : n !== item.order_qty && !reason.trim()
          ? "Укажите причину правки"
          : null;
  const changed = n !== (override?.qty ?? item.order_qty) || reason !== (override?.reason ?? "");

  const steps =
    item.forecast_qty != null && item.safety_stock != null && item.free_stock != null && item.in_transit != null
      ? [
          { sign: "", label: "Прогноз на период", v: item.forecast_qty },
          { sign: "+", label: "Страховой запас", v: item.safety_stock },
          { sign: "−", label: "Свободный остаток", v: item.free_stock },
          { sign: "−", label: "Ожидается (в пути)", v: item.in_transit },
        ]
      : null;

  return (
    <div className="overlay" onClick={onClose}>
      <aside
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="drawer-head">
          <div>
            <div className="eyebrow">{item.supplier_name}</div>
            <h2 id="drawer-title">{item.name}</h2>
            <div className="mono muted small">
              Код 1С {item.erp_code} · артикул {item.supplier_article}
              {item.moq ? ` · кратность ${item.moq}` : ""}
            </div>
          </div>
          <button className="icon-btn" aria-label="Закрыть" onClick={onClose}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>

        <div className="row gap-s wrap">
          {item.urgency && <span className={`pill ${URGENCY[item.urgency].tone}`}>{URGENCY[item.urgency].label}</span>}
          {item.abc_xyz && <span className="pill dark mono">{item.abc_xyz}</span>}
          {item.cover_months != null && <span className="pill muted">покрытие {fmtOne(item.cover_months)} мес</span>}
        </div>

        <section className="card">
          <h3>Почему {fmtInt(item.order_qty)} {item.unit}</h3>
          <p className="explain">{item.explanation}</p>
        </section>

        {steps && (
          <section className="card">
            <h3>Расчёт</h3>
            <ul className="steps">
              {steps.map((s) => (
                <li key={s.label}>
                  <span className="sign mono">{s.sign}</span>
                  <span className="grow">{s.label}</span>
                  <span className="mono">{fmtInt(s.v)}</span>
                </li>
              ))}
              <li className="total">
                <span className="sign mono">=</span>
                <span className="grow">К заказу{item.moq && item.moq > 1 ? `, кратно ${item.moq}` : ""}</span>
                <span className="mono">{fmtInt(item.order_qty)}</span>
              </li>
            </ul>
          </section>
        )}

        <section className="card">
          <h3>Правка закупщика</h3>
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              if (!error) onSave({ qty: n, reason: reason.trim() });
            }}
          >
            <label className="field">
              <span>Количество, {item.unit}</span>
              <input
                type="number"
                inputMode="numeric"
                min={0}
                step={moq ?? 1}
                value={qty}
                onChange={(e) => setQty(e.target.value)}
                aria-invalid={!!error}
                className="mono"
              />
            </label>
            <label className="field">
              <span>Причина</span>
              <textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Например: акция у дилера в ноябре" />
            </label>
            {error && changed && <div className="field-error" role="alert">{error}</div>}
            <div className="row gap">
              <button type="submit" className="btn primary" disabled={!!error || !changed}>
                Сохранить правку
              </button>
              {override && (
                <button type="button" className="btn" onClick={onReset}>
                  Вернуть расчёт ({fmtInt(item.order_qty)})
                </button>
              )}
            </div>
          </form>
        </section>
      </aside>
    </div>
  );
}
