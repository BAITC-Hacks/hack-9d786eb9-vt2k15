"""Прототип расчёта заказа поставщику Systeme Electric.

Читает выгрузки 1С (xlsx) и печатает черновик заказа по SKU:
очищенный спрос, сезонность, ABC/XYZ, страховой запас, кратность.

Запуск:
    pip install pandas openpyxl scipy
    python prototype/order_prototype.py --data data/raw --lead-days 60 --review-days 30

Ожидаемые файлы в --data (имена можно поменять флагами):
    «Товар в пути_SystemElectric на 22.09.2026.xlsx»   — шаблон с продажами, остатками, в пути
    «MOQ SystemElectric.xlsx»                          — кратность
"""
import argparse
import glob
import os

import numpy as np
import pandas as pd
from scipy.stats import norm

SERVICE = {"A": 0.97, "B": 0.95, "C": 0.90}
MONTHS_RU = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
             "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]


def find(data_dir, pattern):
    hits = glob.glob(os.path.join(data_dir, pattern))
    if not hits:
        raise SystemExit(f"Не найден файл по шаблону {pattern} в {data_dir}")
    return hits[0]


def load(data_dir, transit_glob, moq_glob):
    t = pd.read_excel(find(data_dir, transit_glob), sheet_name="TDSheet", header=1)
    t.columns = [str(c).strip() for c in t.columns]
    t = t.dropna(subset=["Код 1с"]).set_index("Код 1с")
    moq = (pd.read_excel(find(data_dir, moq_glob))
           .dropna(subset=["Кратность"])
           .set_index("Номенклатура.Код")["Кратность"])
    return t, moq


def month_columns(t):
    cols = [c for c in t.columns if c.endswith(" г.")]
    # последний месяц в выгрузке неполный (данные до 22-го числа) — в расчёт не берём
    return cols[:-1]


def month_index(col):
    return MONTHS_RU.index(col.split()[0])


def plan(t, moq, lead_days, review_days, horizon_start_month):
    mcols = month_columns(t)
    sales = t[mcols].apply(pd.to_numeric, errors="coerce").fillna(0).clip(lower=0)  # возвраты не уменьшают спрос

    # сезонный индекс компании по двум полным годам, ограничен 0,5–1,5
    total = sales.sum()
    by_month = np.zeros(12)
    for col in mcols[:24]:
        by_month[month_index(col)] += total[col]
    season = np.clip(by_month / by_month.mean(), 0.5, 1.5)

    last12 = sales[mcols[-12:]]
    idx12 = [month_index(c) for c in mcols[-12:]]
    deseason = last12 / season[idx12]
    level = deseason.iloc[:, -6:].mean(axis=1)          # база — последние 6 месяцев (тренд 2026 вниз)
    sigma = deseason.std(axis=1).fillna(0)
    cv = sigma / level.replace(0, np.nan)

    cost = pd.to_numeric(t["СС реал"], errors="coerce").fillna(0)
    turnover = (level * cost).sort_values(ascending=False)
    cum = turnover.cumsum() / turnover.sum()
    abc = pd.cut(cum, [-1, .8, .95, 2], labels=list("ABC")).reindex(t.index).astype(str)
    xyz = pd.cut(cv.fillna(9), [-1, .5, 1, 1e9], labels=list("XYZ")).astype(str)

    months = (lead_days + review_days) / 30
    n = int(np.ceil(months))
    horizon = [(horizon_start_month + i) % 12 for i in range(n)]
    demand = level * season[horizon].sum() * (months / n)

    z = abc.map({k: norm.ppf(v) for k, v in SERVICE.items()})
    safety = z * np.minimum(sigma, level) * np.sqrt(months)
    safety = safety.where(~((abc == "C") & (xyz == "Z")), 0)   # CZ — под заказ клиента

    free = pd.to_numeric(t["Свободный остаток"], errors="coerce").fillna(0)
    transit_col = [c for c in t.columns if c.startswith("СЭ в пути")][0]
    transit = pd.to_numeric(t[transit_col], errors="coerce").fillna(0)
    available = free + transit

    need = (demand + safety - available).clip(lower=0)
    pack = moq.reindex(t.index).fillna(1).clip(lower=1)
    qty = np.ceil(need / pack) * pack
    qty = qty.where(~((need < 0.3 * pack) & (available > level * lead_days / 30)), 0)

    out = pd.DataFrame({
        "Наименование": t["Наименование"], "Класс": abc + xyz,
        "Спрос/мес": level.round(1), "Спрос на L+R": demand.round(0), "Страховой": safety.round(0),
        "Свободно": free, "В пути": transit, "Кратность": pack,
        "Потребность": need.round(0), "Заказ": qty,
        "Покрытие сейчас, мес": (available / level.replace(0, np.nan)).round(1),
        "Покрытие после, мес": ((available + qty) / level.replace(0, np.nan)).round(1),
        "Сумма": (qty * cost).round(0),
    })
    return out, season


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", default=".")
    p.add_argument("--transit-file", default="*Товар в пути*SystemElectric*.xlsx")
    p.add_argument("--moq-file", default="*MOQ*SystemElectric*.xlsx")
    p.add_argument("--lead-days", type=int, default=60)
    p.add_argument("--review-days", type=int, default=30)
    p.add_argument("--horizon-start", type=int, default=10, help="первый месяц горизонта, 1–12")
    p.add_argument("--out", default="order_draft.xlsx")
    a = p.parse_args()

    t, moq = load(a.data, a.transit_file, a.moq_file)
    out, season = plan(t, moq, a.lead_days, a.review_days, a.horizon_start - 1)
    lines = out[out["Заказ"] > 0].sort_values("Покрытие сейчас, мес")
    print("Сезонный индекс янв–дек:", np.round(season, 2))
    print(f"Строк в заказе: {len(lines)}; сумма по себестоимости: {lines['Сумма'].sum() / 1e6:.1f} млн")
    print(lines.groupby(lines["Класс"].str[0])["Сумма"].agg(["count", "sum"]))
    lines.to_excel(a.out)
    print("Черновик заказа сохранён в", a.out)


if __name__ == "__main__":
    main()
