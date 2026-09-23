const int = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 0 });
const one = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });

export const fmtInt = (n: number) => int.format(n);
export const fmtOne = (n: number) => one.format(n);
export const fmtMoney = (n: number) =>
  n >= 1e6 ? `${one.format(n / 1e6)} млн` : n >= 1e3 ? `${int.format(n / 1e3)} тыс` : int.format(n);

export const URGENCY = {
  high: { label: "Срочно", tone: "bad" },
  medium: { label: "Скоро", tone: "warn" },
  low: { label: "Планово", tone: "ok" },
} as const;
