# SystemElectric — AI-планировщик закупок

Hackathon team repository for VT2k15

Сервис считает заказ поставщику Systeme Electric по каждому SKU из выгрузок 1С и объясняет каждую строку: очищенный спрос, сезонность, ABC×XYZ, страховой запас, товар в пути, кратность упаковки.

## Team members

- Nagmetulla Temirlan
- Orynbassar Onggar
- Gabit Bolatkhan

## Сущности

## Что в репозитории

| Путь | Что это |
| --- | --- |
| `docs/design.md` | Дизайн решения: проблема, находки в данных, архитектура, алгоритм, AI-слой, план |
| `design/` | Макеты экранов: план заказа, карточка SKU, риски, параметры |
| `prototype/order_prototype.py` | Прототип расчёта заказа на реальных выгрузках |
| `frontend/` | Веб-интерфейс: загрузка выгрузок, план заказа, карточка позиции, утверждение |

## Фронтенд

![План заказа](frontend/docs/screenshots/plan.png)

```bash
cd frontend
npm install
npm run dev
```

Откройте http://localhost:5173 — интерфейс работает на моках, бэкенд не нужен. С настоящим бэкендом: `VITE_USE_MOCKS=false BACKEND_URL=http://localhost:8000 npm run dev`.

| Загрузка данных | Карточка позиции |
| --- | --- |
| ![Загрузка данных](frontend/docs/screenshots/import-attach.png) | ![Карточка позиции](frontend/docs/screenshots/item.png) |

Экраны, сценарии моков и контракт API — в [frontend/README.md](frontend/README.md).

## Прототип

```bash
pip install -r prototype/requirements.txt
# положить выгрузки 1С в data/raw/ (в git не коммитятся)
python prototype/order_prototype.py --data data/raw --lead-days 60 --review-days 30
```

На выгрузке от 22.09.2026 при L = 60 и R = 30 дней: 218 строк на 80,3 млн по себестоимости, из них 65 строк класса A на 61,5 млн.
