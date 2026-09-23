import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, HttpError } from "../api/client";
import type { OrderItem } from "../api/types";
import { fmtInt, fmtMoney } from "../format";
import { OrderTable, type Override } from "../components/OrderTable";
import { ItemDrawer } from "../components/ItemDrawer";
import { ApproveDialog } from "../components/ApproveDialog";
import { EmptyState, ErrorState, TableSkeleton } from "../components/States";

type Urgency = NonNullable<OrderItem["urgency"]>;

export function PlanPage({ onGoImport }: { onGoImport: () => void }) {
  const { data, isPending, isError, error, refetch, isFetching } = useQuery({
    queryKey: ["orders"],
    queryFn: () => api.orders(),
  });

  const [supplier, setSupplier] = useState<string>("all");
  const [urgency, setUrgency] = useState<Urgency | "all">("all");
  const [q, setQ] = useState("");
  const [overrides, setOverrides] = useState<Record<string, Override>>({});
  const [openCode, setOpenCode] = useState<string | null>(null);
  const [approveOpen, setApproveOpen] = useState(false);
  const [approvedMsg, setApprovedMsg] = useState<string | null>(null);

  const items = useMemo(() => data?.items ?? [], [data]);
  const suppliers = useMemo(() => {
    const m = new Map<string, { id: string; name: string; count: number }>();
    for (const i of items) {
      const s = m.get(i.supplier_id) ?? { id: i.supplier_id, name: i.supplier_name, count: 0 };
      s.count += 1;
      m.set(i.supplier_id, s);
    }
    return [...m.values()];
  }, [items]);

  const hasUrgency = items.some((i) => i.urgency);
  const hasCost = items.some((i) => i.unit_cost != null);
  const qtyOf = (i: OrderItem) => overrides[i.erp_code]?.qty ?? i.order_qty;

  const visible = items.filter((i) => {
    if (supplier !== "all" && i.supplier_id !== supplier) return false;
    if (urgency !== "all" && i.urgency !== urgency) return false;
    if (q) {
      const s = q.toLowerCase();
      return i.name.toLowerCase().includes(s) || i.erp_code.includes(s) || i.supplier_article.toLowerCase().includes(s);
    }
    return true;
  });
  const inScope = items.filter((i) => supplier === "all" || i.supplier_id === supplier);
  const totalCost = inScope.reduce((acc, i) => acc + qtyOf(i) * (i.unit_cost ?? 0), 0);
  const urgentCount = inScope.filter((i) => i.urgency === "high").length;
  const overrideCount = inScope.filter((i) => overrides[i.erp_code]).length;
  const openItem = items.find((i) => i.erp_code === openCode) ?? null;

  return (
    <>
        <header className="page-head">
          <div>
            <div className="eyebrow">Рекомендованный заказ поставщикам</div>
            <h1>План заказа</h1>
          </div>
          <div className="row gap">
            <button className="btn" onClick={() => refetch()} disabled={isFetching}>
              {isFetching ? "Обновляю…" : "Обновить"}
            </button>
            <button className="btn primary" onClick={() => setApproveOpen(true)} disabled={!inScope.length}>
              Утвердить и выгрузить
            </button>
          </div>
        </header>

        {approvedMsg && (
          <div className="banner ok" role="status">
            <span>{approvedMsg}</span>
            <button className="link" onClick={() => setApprovedMsg(null)}>Скрыть</button>
          </div>
        )}

        {isPending ? (
          <TableSkeleton />
        ) : isError ? (
          <ErrorState
            message={error.message}
            requestId={error instanceof HttpError ? error.requestId : undefined}
            onRetry={() => refetch()}
          />
        ) : !items.length ? (
          <EmptyState onGoImport={onGoImport} />
        ) : (
          <>
            <section className="kpis" aria-label="Итоги">
              <Kpi label="Позиций к заказу" value={fmtInt(inScope.length)} sub={`поставщиков: ${suppliers.length}`} />
              {hasCost && <Kpi label="Сумма по себестоимости" value={fmtMoney(totalCost)} sub="с учётом правок" />}
              {hasUrgency && <Kpi label="Срочно" value={fmtInt(urgentCount)} sub="покрытие меньше месяца" tone="bad" />}
              <Kpi label="Правок закупщика" value={fmtInt(overrideCount)} sub="сохраняются до утверждения" />
            </section>

            <div className="tabs" role="tablist" aria-label="Поставщики">
              <Tab active={supplier === "all"} onClick={() => setSupplier("all")}>
                Все поставщики <span className="count">{items.length}</span>
              </Tab>
              {suppliers.map((s) => (
                <Tab key={s.id} active={supplier === s.id} onClick={() => setSupplier(s.id)}>
                  {s.name} <span className="count">{s.count}</span>
                </Tab>
              ))}
            </div>

            <div className="toolbar">
              {hasUrgency && (
                <div className="row gap-s" role="group" aria-label="Срочность">
                  {(["all", "high", "medium", "low"] as const).map((u) => (
                    <button key={u} className={`chip ${urgency === u ? "on" : ""}`} onClick={() => setUrgency(u)}>
                      {{ all: "Все", high: "Срочно", medium: "Скоро", low: "Планово" }[u]}
                    </button>
                  ))}
                </div>
              )}
              <div className="spacer" />
              <label className="search">
                <span className="sr-only">Поиск</span>
                <input
                  type="search"
                  placeholder="Код 1С, артикул, название"
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                />
              </label>
            </div>

            <OrderTable items={visible} overrides={overrides} onOpen={setOpenCode} />
            <div className="foot-note">
              Показано {fmtInt(visible.length)} из {fmtInt(items.length)}. Клик по строке — обоснование и правка количества.
            </div>
          </>
        )}

      {openItem && (
        <ItemDrawer
          item={openItem}
          override={overrides[openItem.erp_code]}
          onClose={() => setOpenCode(null)}
          onSave={(o) => setOverrides((p) => ({ ...p, [openItem.erp_code]: o }))}
          onReset={() =>
            setOverrides((p) => {
              const n = { ...p };
              delete n[openItem.erp_code];
              return n;
            })
          }
        />
      )}

      {approveOpen && (
        <ApproveDialog
          items={inScope}
          overrides={overrides}
          supplierName={supplier === "all" ? null : suppliers.find((s) => s.id === supplier)?.name ?? null}
          onClose={() => setApproveOpen(false)}
          onDone={(msg) => {
            setApproveOpen(false);
            setApprovedMsg(msg);
          }}
        />
      )}
    </>
  );
}

function Kpi({ label, value, sub, tone }: { label: string; value: string; sub: string; tone?: "bad" }) {
  return (
    <div className="kpi">
      <div className="kpi-label">{label}</div>
      <div className={`kpi-value ${tone ?? ""}`}>{value}</div>
      <div className="kpi-sub">{sub}</div>
    </div>
  );
}

function Tab({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button role="tab" aria-selected={active} className={`tab ${active ? "on" : ""}`} onClick={onClick}>
      {children}
    </button>
  );
}
