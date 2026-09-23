export type Route = "plan" | "import";

const NAV: { label: string; route?: Route; href?: string }[] = [
  { label: "План заказа", route: "plan", href: "#/" },
  { label: "Риски" },
  { label: "Спросить AI" },
  { label: "Загрузка данных", route: "import", href: "#/import" },
  { label: "Параметры" },
];

export function Sidebar({ route }: { route: Route }) {
  return (
    <nav className="sidebar" aria-label="Разделы">
      <div className="brand">
        <div className="brand-name">Закупки SE</div>
        <div className="brand-sub">Электрокомплект · Алматы</div>
      </div>
      {NAV.map((n) =>
        n.route ? (
          <a
            key={n.label}
            href={n.href}
            className={`nav-link ${route === n.route ? "on" : ""}`}
            aria-current={route === n.route ? "page" : undefined}
          >
            {n.label}
          </a>
        ) : (
          <span key={n.label} className="nav-link off" aria-disabled="true" title="Экран в разработке">
            {n.label} <span className="soon">скоро</span>
          </span>
        ),
      )}
      <div className="spacer" />
      <div className="side-note">{import.meta.env.VITE_USE_MOCKS === "false" ? "Данные: бэкенд" : "Данные: моки"}</div>
    </nav>
  );
}
