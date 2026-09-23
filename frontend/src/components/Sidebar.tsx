const NAV = [
  { label: "План заказа", ready: true },
  { label: "Риски", ready: false },
  { label: "Спросить AI", ready: false },
  { label: "Загрузка данных", ready: false },
  { label: "Параметры", ready: false },
];

export function Sidebar() {
  return (
    <nav className="sidebar" aria-label="Разделы">
      <div className="brand">
        <div className="brand-name">Закупки SE</div>
        <div className="brand-sub">Электрокомплект · Алматы</div>
      </div>
      {NAV.map((n) =>
        n.ready ? (
          <a key={n.label} href="#" className="nav-link on" aria-current="page">
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
