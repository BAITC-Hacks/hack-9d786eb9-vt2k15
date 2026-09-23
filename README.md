# SystemElectric — AI-планировщик закупок

Hackathon team repository for VT2k15

Сервис считает заказ поставщику Systeme Electric по каждому SKU из выгрузок 1С и объясняет каждую строку: очищенный спрос, сезонность, ABC×XYZ, страховой запас, товар в пути, кратность упаковки.

## Team members

- Nagmetulla Temirlan
- Orynbassar Onggar
- Gabit Bolatkhan

## Что в репозитории

| Путь | Что это |
| --- | --- |
| `docs/design.md` | Дизайн решения: проблема, находки в данных, архитектура, алгоритм, AI-слой, план |
| `design/` | Макеты 4 экранов (план заказа, карточка SKU, риски, параметры) |
| `prototype/order_prototype.py` | Прототип расчёта заказа на реальных выгрузках |

## Прототип

```bash
pip install -r prototype/requirements.txt
# положить выгрузки 1С в data/raw/ (в git не коммитятся)
python prototype/order_prototype.py --data data/raw --lead-days 60 --review-days 30
```

На выгрузке от 22.09.2026 при L = 60 и R = 30 дней: 218 строк на 80,3 млн по себестоимости, из них 65 строк класса A на 61,5 млн.

Живые версии: [дизайн-документ](https://claude.ai/code/artifact/de90798c-c59f-403e-8c23-fd86ee881b08) · [макет](https://claude.ai/artifact/BA3GbY4Xirgb3Gnoo5x1KX)
