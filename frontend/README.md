# Frontend — план заказа поставщикам

React + Vite + TypeScript. Данные — только с бэкенда; пока бэкенд не готов, работают моки (MSW).

## Запуск

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173, моки включены
```

Сценарии моков — параметр в адресе:

| URL | Что показывает |
| --- | --- |
| `/` | 218 позиций Systeme Electric (реальные данные из прототипа) + предлагаемые поля |
| `/?mock=base` | Только поля из `docs/front data.txt` — так UI выглядит на текущем контракте |
| `/?mock=slow` | Загрузка 4 с (скелетон) |
| `/?mock=empty` | Пустой список |
| `/?mock=error` | Ошибка 503 с `request_id` |

Настоящий бэкенд: `VITE_USE_MOCKS=false BACKEND_URL=http://localhost:8000 npm run dev` — запросы `/api/*` проксируются.

## Контракт

- `GET /api/orders` → `{ items: OrderItem[] }` — поля из `docs/front data.txt`: `erp_code`, `supplier_id`, `supplier_name`, `supplier_article`, `name`, `unit`, `order_qty`, `explanation`.
- `POST /api/orders/approve` `{ supplier_id, lines: [{ erp_code, order_qty, reason? }] }` → `{ order_id, approved_at }` — **предложение бэкенду**, пути пока нет.
- Необязательные поля, которые UI уже умеет показывать, если бэкенд их добавит: `urgency`, `abc_xyz`, `forecast_qty`, `safety_stock`, `free_stock`, `in_transit`, `moq`, `demand_per_month`, `cover_months`, `unit_cost` (`src/api/types.ts`). Нет поля — нет колонки.

## Что готово

- План заказа: итоги, вкладки по поставщикам, фильтр срочности, поиск, таблица.
- Карточка позиции: обоснование, расчёт по шагам, правка количества с причиной (проверка кратности).
- Утверждение: подтверждение, `POST /approve` по каждому поставщику, выгрузка CSV для 1С (`;`, UTF-8 BOM).
- Состояния: загрузка, ошибка с повтором, пусто. Тёмная тема, адаптив до мобильного.

Риски, «Спросить AI», загрузка данных и параметры — в макете (`design/`), в навигации помечены «скоро».
