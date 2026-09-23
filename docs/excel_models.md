# Excel → типизированные модели Kotlin

Этот документ описывает **импортированные данные**, которые backend извлекает из 12 поддерживаемых Excel: шести IEK и шести Systeme Electric. В одном запросе можно загрузить полный комплект одного поставщика или оба комплекта. В `ExcelFileStore.currentUpload` хранятся значения отчётов, а не файлы или `ByteArray`.

Сами report DTO сохраняют смысл исходных файлов. Отдельный слой planning соединяет их по коду товара и через `GET /api/orders` формирует новые рекомендации, не изменяя загруженный снимок. Его входные модели, правила выбора источников, формулы и HTTP-ответ описаны в [order_calculation.md](order_calculation.md); их не следует путать с исходными полями `Заказ`, `Запас` и коэффициентами Excel.

Все экземпляры `ExcelFileStore` используют общую `ConcurrentHashMap<String, ExcelUpload>` в `companion object`. Под ключом `current` находится последний успешно загруженный снимок. Чтение его не удаляет; следующая успешная загрузка атомарно заменяет **весь снимок**. Отсутствующий в новом запросе поставщик становится `null`, даже если его данные были загружены раньше. Объединения с предыдущим снимком нет. Данные живут в оперативной памяти до замены или остановки приложения.

Основа контракта: [IekData.kt](../src/main/kotlin/kz/hackalem/backend/IekData.kt), [SystemeData.kt](../src/main/kotlin/kz/hackalem/backend/SystemeData.kt), [ExcelReader.kt](../src/main/kotlin/kz/hackalem/backend/ExcelReader.kt), [ExcelFileStore.kt](../src/main/kotlin/kz/hackalem/backend/ExcelFileStore.kt). Исходные заголовки, формулы и особенности файлов подробно разобраны в [словаре IEK](iek_fields.md) и [словаре Systeme Electric](systeme_fields.md).

Буквы колонок и номера строк ниже относятся к предоставленным файлам. Периоды берутся из заголовков: например, новый заголовок `янв. 2027` означает новый `YearMonth`, а не переименование поля модели. Это не универсальный импорт произвольного Excel: парсеры ожидают известные структуры отчётов.

## 1. Дерево данных и карта 12 файлов

`ExcelUpload` содержит:

| Поле | Kotlin-тип | Источник и смысл |
|---|---|---|
| `iek` | `IekData?` | Шесть отчётов IEK из полей формы с префиксом `iek`; `null`, если IEK не передан |
| `systeme` | `SystemeData?` | Шесть отчётов Systeme Electric из полей с префиксом `systeme`; `null`, если Systeme не передан |
| `issues` | `List<ExcelIssue>` | Замечания к ячейкам всех разобранных файлов |

Хотя бы одна из групп `iek` / `systeme` должна присутствовать. Каждая переданная группа содержит все шесть отчётов; отдельные отчёты внутри неё не nullable. В таблице ниже пути `iek.moq` / `systeme.moq` и аналогичные предполагают, что соответствующая группа присутствует; в Kotlin для доступа к nullable-группе нужен `?.` или предварительная проверка.

У `IekData` и `SystemeData` по шесть полей. Их имена, точные типы и исходные файлы:

| Multipart-поле | Исходный Excel | Поле внутри `ExcelUpload` и report DTO | Основная row DTO |
|---|---|---|---|
| `iekMoq` | `MOQ  ИЭК.xlsx` | `iek.moq: IekMoqData` | `IekMoqRow` |
| `iekSalesDynamics` | `Динамика продаж_2025-2026.xlsx` | `iek.salesDynamics: IekSalesDynamicsData` | `IekSalesDynamicsRow` |
| `iekMonthlyStocks` | `Ежемесячные остатки продукции за последние 2 года  ИЭК.xlsx` | `iek.monthlyStocks: IekMonthlyStocksData` | `IekMonthlyStockRow` |
| `iekMonthlySales` | `Ежемесячные продажи в количественном выражении за последние 2 года.xlsx` | `iek.monthlySales: IekMonthlySalesData` | `IekMonthlySalesRow` |
| `iekIncomingShipments` | `Путь ИЭК 22.09.2026.xlsx` | `iek.incomingShipments: IekIncomingShipmentsData` | `IekIncomingShipmentRow` |
| `iekSeasonality` | `Сезонность ИЭК.xlsx` | `iek.seasonality: IekSeasonalityData` | `IekYearlySeasonalityRow` и вспомогательные блоки |
| `systemeMoq` | `MOQ SystemElectric.xlsx` | `systeme.moq: SystemeMoqData` | `SystemeMoqRow` |
| `systemeSalesDynamics` | `Динамика продаж_Syseme Electric_2025-2026.xlsx` | `systeme.salesDynamics: SystemeSalesDynamicsData` | `SystemeSalesDynamicsRow` |
| `systemeMonthlyStocks` | `Ежемесячные остатки SystemElectric 2024-2026.xlsx` | `systeme.monthlyStocks: SystemeMonthlyStocksData` | `SystemeMonthlyStockRow` |
| `systemeMonthlySales` | `Ежемесячные продажи в кол-м выражении SystemElectric 2024-2026.xlsx` | `systeme.monthlySales: SystemeMonthlySalesData` | `SystemeMonthlySalesRow` |
| `systemeIncomingShipments` | `Товар в пути_SystemElectric на 22.09.2026.xlsx` | `systeme.incomingShipments: SystemeIncomingShipmentsData` | `SystemeIncomingShipmentRow` |
| `systemeSeasonality` | `Сезонность SystemElectric 2024-2026.xlsx` | `systeme.seasonality: SystemeSeasonalityData` | `SystemeSeasonalMonthRow` и вспомогательные блоки |

Роль файла задаётся именем multipart-поля; исходное имя файла сохраняется как `fileName`. Каждый из 12 report DTO имеет общее поле `fileName: String`. Ниже оно не повторяется в каждой таблице. `rows` означает строки данных, а `totals` — отдельные итоговые строки, которые нельзя повторно считать товарами или операциями.

## 2. Общие типы, единицы и ошибки

### Типы значений

| Kotlin-тип / поле | Как читать |
|---|---|
| `String` / `String?` | Исходный текст; `?` допускает отсутствие значения. Код товара и артикул — текст, даже если похожи на числа |
| `BigDecimal?` | Десятичное число без автоматического округления к целым. Единица определяется конкретным полем/отчётом |
| `Int` / `Int?` | Целое число: год, номер строки, порядковый номер или номер месяца |
| `LocalDateTime?` | Локальная дата и время операции; часовой пояс исходником не задан |
| `LocalDate?` | Календарная дата документа или ожидаемого поступления, без времени |
| `YearMonth` | Год и месяц, например `2026-09`; это не дата начала/конца остатка сама по себе |
| `Month` | Месяц года из `java.time.Month`, например `SEPTEMBER`; года внутри значения нет |
| `List<T>` | Список записей указанного типа; сам список не nullable |
| `sourceRow: Int` | Физический номер строки Excel, начиная с 1; не ID товара/документа |

`null`, числовой ноль и отрицательное количество имеют разный смысл. Отрицательные продажи сохраняются. Единицы не конвертируются: количество в метрах нельзя складывать с количеством бухт. Если единица или валюта в исходном блоке не подписана, модель не придумывает `шт`, килограммы или тенге.

### `MonthlyValue`

Один тип используется во всех месячных товарных рядах. Назначение числа задаёт поле, в котором находится список: `sales`, `monthlySales`, `openingStocks` или `monthlyStocks`. В годовых строках сезонности год хранится отдельно от месяца: там используются `IekCalendarMonthValue` и `SystemeCalendarMonthValue`.

| Поле | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `month` | `YearMonth` | Месячный заголовок | Конкретный календарный месяц |
| `value` | `BigDecimal?` | Ячейка на пересечении строки и месячной колонки | Продажи/остаток в единице товара; если она не подписана, единица не придумывается |

Например, 33 месячные колонки становятся списком из 33 `MonthlyValue`, а не 33 свойствами DTO. Итоговая колонка `Итого` в этот список не входит.

### `ExcelIssue`

| Поле | Kotlin-тип | Источник | Смысл |
|---|---|---|---|
| `fileName` | `String` | Имя загрузки | Какой файл вызвал замечание |
| `sheet` | `String` | Имя листа | Где возникла проблема |
| `cell` | `String` | Адрес Excel, например `E17` | Какая ячейка требует внимания |
| `message` | `String` | Проверка парсера | Читаемое описание проблемы |
| `rawValue` | `String?` | Исходное значение, если доступно | Что не удалось корректно интерпретировать |

Ошибка отдельной числовой ячейки даёт `null` и замечание. Ошибки структуры/файла отменяют импорт целиком, сохраняя предыдущий комплект. Формулы не пересчитываются: используется сохранённый в `.xlsx` результат. Внешний каталог, закешированный внутри IEK MOQ как external link, не является отдельным импортируемым отчётом; берётся результат ячейки MOQ, а не весь каталог.

## 3. IEK: товарные отчёты

### 3.1. `IekMoqData` → `IekMoqRow`

Лист `Лист7`, шапка 1, данные со строки 2. Поля report DTO: общий `fileName: String` и `rows: List<IekMoqRow>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки листа | Происхождение записи |
| `ordinal` | `Int?` | A, `№` | Порядковый номер отчёта |
| `productCode` | `String?` | B, `Код 1с` | Внутренний код товара |
| `supplierArticle` | `String?` | C, `Артикул поставщика` | Артикул IEK |
| `productName` | `String?` | D, `Наименование` | Наименование товара |
| `minimumShipmentQuantity` | `BigDecimal?` | E, `Мин. разр. к отгр.` | Минимальное разрешённое количество отгрузки из сохранённого результата формулы; единица отдельно не указана |

Это **минимальная отгрузка**, а не поле кратности из внешнего прайса. `#N/A` не заменяется на 1 или 0. Повторяющиеся коды сохраняются отдельными строками; `productCode` сам по себе не уникальный ключ этого списка.

### 3.2. `IekSalesDynamicsData` → `IekSalesDynamicsRow`

Лист `Лист_1`, шапка 1, данные со строки 2. Report DTO: общий `fileName: String`, `rows: List<IekSalesDynamicsRow>`, `totals: List<IekSalesDynamicsTotal>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение операции |
| `occurredAt` | `LocalDateTime?` | A, `Дата` | Дата и время операции, исходно текст |
| `documentNumber` | `String?` | B, `Номер` | Номер документа, не уникальный номер товарной строки |
| `document` | `String?` | C, `Документ` | Полное текстовое представление документа |
| `productCode` | `String?` | D, `Код` | Код 1С |
| `productName` | `String?` | E, `Номенклатура` | Название товара |
| `unit` | `String?` | F, `Ед.` | Единица количества, например `шт` или `м` |
| `warehouse` | `String?` | G, `Склад` | Склад операции |
| `quantity` | `BigDecimal?` | H, `Количество` | Количество в `unit`, с исходным знаком |

`IekSalesDynamicsTotal` содержит `sourceRow: Int` — номер строки `Итого`, и `quantity: BigDecimal?` — её H. Это контрольный итог отчёта, а не ещё одна операция. Номер и описание документа не заменяют отсутствующий ID клиента.

### 3.3. `IekMonthlyStocksData` → `IekMonthlyStockRow`

Лист `Лист_1`, шапка 1–3, данные со строки 4. Report DTO: общий `fileName: String`, `rows: List<IekMonthlyStockRow>`, `totals: List<IekMonthlyStockTotal>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение записи |
| `productName` | `String?` | A, `Номенклатура` | Название товара |
| `unit` | `String?` | B, `Ед.` | Единица остатка |
| `productCode` | `String?` | C, `Номенклатура.Код` | Код 1С |
| `openingStocks` | `List<MonthlyValue>` | D:AJ, месяцы; нижняя шапка `Количество / нач. остаток` | Остатки на начало каждого месяца, в `unit` |
| `periodOpeningStock` | `BigDecimal?` | AK, `Итого / Количество / нач. остаток` | Начальный остаток всего периода; это не сумма месячных остатков |

`IekMonthlyStockTotal` содержит `sourceRow: Int`, `openingStocks: List<MonthlyValue>` и `periodOpeningStock: BigDecimal?` из тех же колонок итоговой строки. Итоги могут объединять товары с разными единицами; они сохраняются как показатели исходного отчёта.

### 3.4. `IekMonthlySalesData` → `IekMonthlySalesRow`

Лист `Лист_1`, шапка 1–2, данные со строки 3. Report DTO: общий `fileName: String`, `rows: List<IekMonthlySalesRow>`, `totals: List<IekMonthlySalesTotal>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение записи |
| `productName` | `String?` | A, `Номенклатура` | Название товара |
| `productCode` | `String?` | B, `Номенклатура.Код` | Код 1С |
| `sales` | `List<MonthlyValue>` | C:AI, месяцы / `Количество` | Месячное количество продаж; единицы в этом файле нет |
| `periodTotal` | `BigDecimal?` | AJ, `Итого / Количество` | Исходное количество продаж за весь представленный период |

`IekMonthlySalesTotal` содержит `sourceRow: Int`, `sales: List<MonthlyValue>` и `periodTotal: BigDecimal?` из итоговой строки. Для единицы товара потребуется сопоставление с другим отчётом; парсер этого не делает.

### 3.5. `IekIncomingShipmentsData` → партии и строки товаров

Лист `Лист4`, шапка 1, данные со строки 2. Метаданные партии находятся **в заголовке колонки**, а количества — в товарных строках.

| Поле report DTO | Kotlin-тип | Источник / смысл |
|---|---|---|
| `fileName` | `String` | Имя загруженного Excel |
| `shipments` | `List<IekShipmentColumn>` | Заголовки партий D:I; один элемент на колонку партии |
| `rows` | `List<IekIncomingShipmentRow>` | Товарные строки |
| `totals` | `List<IekIncomingShipmentTotal>` | Итоговые строки, если присутствуют |

`IekIncomingShipmentRow`:

| Поле | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение записи |
| `productCode` | `String?` | A, `Код 1с` | Код 1С |
| `supplierArticle` | `String?` | B, `Артикул ИЭК` | Артикул IEK |
| `productName` | `String?` | C, `Наименование` | Название, включая исходные примечания |
| `quantities` | `List<IekShipmentQuantity>` | Ячейки D:I | Количество товара отдельно по каждой партии; единица отдельной колонкой не указана |

`IekShipmentColumn`:

| Поле | Kotlin-тип | Источник | Смысл |
|---|---|---|---|
| `sourceColumn` | `String` | Буква D, E, … | Ключ партии внутри этого отчёта |
| `sourceHeader` | `String?` | Полный заголовок колонки | Сохранённая исходная подпись |
| `documentPrefix` | `String?` | Часть перед номером заказа, например `РФ`, `ПП` | Исходный префикс; расшифровка не придумывается |
| `orderNumber` | `String?` | Например `УТ-7583` | Номер из заголовка |
| `orderDate` | `LocalDate?` | Например `от 31 августа 2026 г.` | Дата документа |
| `expectedBy` | `LocalDate?` | Например `поступление до 10.10.2026` | Предельная ожидаемая дата поступления, не факт прихода |

`IekShipmentQuantity` содержит `sourceColumn: String` — ссылку на `shipments.sourceColumn`, и `quantity: BigDecimal?` — соответствующее количество. `IekIncomingShipmentTotal` содержит `sourceRow: Int` и `quantities: List<IekShipmentQuantity>` для итоговой строки.

Например, количество из `D57` соединяется с метаданными партии D через `sourceColumn = "D"`. Такой ключ действует только внутри конкретного файла. Текст «ЗАКУПАЮТСЯ БУХТАМИ, САДЯТСЯ МЕТРАЖОМ» остаётся в названии: автоматической конвертации единиц нет.

## 4. IEK: все блоки сезонности

Лист `Сезонность`. В модели сохранён также нижний блок в скрытых строках. Значения «Продажи» здесь агрегированы; исходник не подписывает их единицу и валюту. Называть их количеством штук или денежной выручкой без подтверждения нельзя.

### `IekSeasonalityData`: состав отчёта

| Поле | Kotlin-тип | Исходный блок / смысл |
|---|---|---|
| `fileName` | `String` | Имя загрузки |
| `annualSales` | `List<IekAnnualSalesRow>` | A3:N6: исходные годовые строки |
| `yearlySeasonality` | `List<IekYearlySeasonalityRow>` | B10:L22: основной блок из 12 месячных строк |
| `yearlySeasonalityTotals` | `List<IekYearlySeasonalityTotal>` | Строка 23: итоги/средние основного блока |
| `annualBases` | `List<IekAnnualSeasonalityBaseRow>` | M10:O13: годовые базы коэффициентов |
| `normalizedSeasonality` | `List<IekNormalizedSeasonalityRow>` | B27:H39: нормализация и примечания |
| `normalizationTotals` | `List<IekSeasonalityNormalizationTotal>` | Строка 40: контрольные итоги нормализации |

### `IekAnnualSalesRow` и `IekCalendarMonthValue`

| Поле `IekAnnualSalesRow` | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 4–6 | Происхождение |
| `year` | `Int?` | A, `год` | Календарный год |
| `monthlySales` | `List<IekCalendarMonthValue>` | B:M, `янв`…`дек` | Агрегированные месячные показатели, единица неизвестна |
| `total` | `BigDecimal?` | N, `ИТОГО` | Сохранённый годовой итог доступных месяцев |

`IekCalendarMonthValue` содержит `month: Int` — номер месяца 1–12 из заголовка, и `value: BigDecimal?` — число в месячной ячейке. Год берётся из родительского `IekAnnualSalesRow.year`. Верхние пустые месяцы 2026 остаются пустыми; они не означают подтверждённые нулевые продажи.

### `IekYearlySeasonalityRow` и `IekSeasonalityYearValues`

| Поле `IekYearlySeasonalityRow` | Kotlin-тип | Источник | Смысл |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 11–22 | Происхождение |
| `monthLabel` | `String?` | B, `Месяц` | Исходная подпись месяца |
| `month` | `Int?` | Разбор B | Номер месяца 1–12 |
| `years` | `List<IekSeasonalityYearValues>` | C:K, группы `Продажи <год>` | Показатели каждого года для этого месяца |
| `normalizedCoefficient` | `BigDecimal?` | L, `СЕЗОННОСТЬ` | Безразмерный итоговый коэффициент; ссылка на нижнюю нормализацию |

| Поле `IekSeasonalityYearValues` | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `year` | `Int` | Заголовок `Продажи 2024/2025/2026` | Год группы, извлечённый из заголовка |
| `sales` | `BigDecimal?` | C / F / I | Агрегированные продажи месяца, единица неизвестна |
| `coefficient` | `BigDecimal?` | D / G / J, `Коэф. сезонности…` | Безразмерный индекс: продажи месяца / средние месячные продажи года |
| `annualShare` | `BigDecimal?` | E / H / K, `Доля в году…` | Доля, например 0,08 = 8%; не число процентных пунктов |

Исходные формулы на октябрь–декабрь 2026 могут сохранять нули из пустых верхних ячеек. Это значение формулы, а не доказательство фактических продаж за будущий месяц.

### Итоги основного блока

| Поле `IekYearlySeasonalityTotal` | Kotlin-тип | Источник / смысл |
|---|---|---|
| `sourceRow` | `Int` | Строка 23 |
| `years` | `List<IekSeasonalityYearTotal>` | Контрольные итоги C:K по годам |
| `normalizedCoefficientAverage` | `BigDecimal?` | L23, среднее итогового сезонного коэффициента |

| Поле `IekSeasonalityYearTotal` | Kotlin-тип | Источник / смысл |
|---|---|---|
| `year` | `Int` | Год соответствующей группы заголовков |
| `salesTotal` | `BigDecimal?` | C23 / F23 / I23, сумма продаж года, единица неизвестна |
| `coefficientAverage` | `BigDecimal?` | D23 / G23 / J23, средний безразмерный индекс |
| `annualShareTotal` | `BigDecimal?` | E23 / H23 / K23, сумма долей |

### `IekAnnualSeasonalityBaseRow`

| Поле | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 11–13 | Происхождение |
| `year` | `Int?` | M, `Год` | Год базы |
| `monthlyAverage` | `BigDecimal?` | N, `Среднемес.` | Среднее заполненных месячных значений; единица неизвестна |
| `annualTotal` | `BigDecimal?` | O, `Итого за год` | Итог доступных месяцев; та же неизвестная единица |

Для неполного 2026 среднее основано на заполненных месяцах, а не автоматически на 12.

### `IekNormalizedSeasonalityRow`, коэффициенты и итоги

| Поле `IekNormalizedSeasonalityRow` | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 28–39 | Происхождение |
| `monthLabel` | `String?` | B, `Месяц` | Исходная подпись |
| `month` | `Int?` | Разбор B | Номер месяца |
| `coefficients` | `List<IekSeasonalityCoefficient>` | C:D, `Коэф. 2025`, `Коэф. 2026` | Сезонные коэффициенты по годам; годы не зашиты в имена полей |
| `averageCoefficient` | `BigDecimal?` | E, `Средний коэф.` | Безразмерное среднее годовых коэффициентов |
| `normalizedCoefficient` | `BigDecimal?` | F, `Норм. коэф.` | Коэффициент, нормализованный к среднему 1 |
| `annualShare` | `BigDecimal?` | G, `Доля в году` | Доля года: нормализованный коэффициент / 12 |
| `note` | `String?` | H, без заголовка | Примечание, включая пояснение прогноза 2026 для будущих месяцев |

`IekSeasonalityCoefficient` содержит `year: Int` — год из заголовка коэффициента, и `value: BigDecimal?` — безразмерное значение из C/D. `IekSeasonalityNormalizationTotal` содержит `sourceRow: Int` — строку 40, `normalizedCoefficientAverage: BigDecimal?` — F40 и `annualShareTotal: BigDecimal?` — G40. Эти контрольные значения не добавляются как тринадцатый месяц.

## 5. Systeme Electric: товарные отчёты

### 5.1. `SystemeMoqData` → `SystemeMoqRow`

Лист `Лист_1`, данные со строки 3. Report DTO: общий `fileName: String`, `rows: List<SystemeMoqRow>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение |
| `reportNumber` | `Int?` | A, `№` | Номер строки отчёта |
| `productName` | `String?` | B, `Номенклатура` | Название товара |
| `productCode` | `String?` | C, `Номенклатура.Код` | Код 1С |
| `supplierArticle` | `String?` | D, `Артикул` | Артикул поставщика |
| `orderMultiple` | `BigDecimal?` | E, `Кратность` | Кратность заказа в единицах товара; единица отдельным полем не дана |

Здесь источник содержит кратность, а не отдельное минимальное количество заказа. Имя файла `MOQ` не меняет смысл заголовка.

### 5.2. `SystemeSalesDynamicsData` → `SystemeSalesDynamicsRow`

Лист `Лист_1`, данные со строки 2. Report DTO: общий `fileName: String`, `rows: List<SystemeSalesDynamicsRow>`, `totals: List<SystemeSalesDynamicsTotal>`.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение операции |
| `documentDateTime` | `LocalDateTime?` | A, `Дата` | Дата и время операции |
| `documentNumber` | `String?` | B, `Номер` | Номер документа |
| `documentDescription` | `String?` | C, `Документ` | Полное описание документа |
| `productCode` | `String?` | D, `Код` | Код 1С |
| `productName` | `String?` | E, `Номенклатура` | Название товара |
| `unit` | `String?` | F, `Ед.` | Единица количества |
| `warehouse` | `String?` | G, `Склад` | Склад операции |
| `quantity` | `BigDecimal?` | H, `Количество` | Количество в `unit`, со знаком |

`SystemeSalesDynamicsTotal` содержит `sourceRow: Int` — номер итоговой строки и `quantity: BigDecimal?` — значение H этой строки. Как и для IEK, клиент в этом отчёте не идентифицирован.

### 5.3. `SystemeMonthlyStocksData` → `SystemeMonthlyStockRow`

Лист `Лист_1`, шапка 1–3, данные со строки 4. Report DTO: общий `fileName: String`, `rows: List<SystemeMonthlyStockRow>`; отдельного `totals` в этой модели нет.

| Поле строки | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение |
| `reportNumber` | `Int?` | A, `№` | Порядковый номер |
| `productName` | `String?` | B, `Номенклатура` | Название товара |
| `productCode` | `String?` | C, `Номенклатура.Код` | Код 1С |
| `unit` | `String?` | D, `Ед.изм` | Единица остатка |
| `monthlyStocks` | `List<MonthlyValue>` | E:AK, месячные заголовки | Остатки в `unit`; момент среза внутри месяца источником не уточнён |

В отличие от IEK, здесь нельзя уверенно назвать значения остатками на начало месяца. Отдельного склада в товарной строке нет.

### 5.4. `SystemeMonthlySalesData` → `SystemeMonthlySalesRow`

Основной лист `Лист_1`, шапка 1–2, данные со строки 3. Дополнительный `Лист1` скрыт, но его сезонность сохраняется отдельно.

| Поле report DTO | Kotlin-тип | Источник / смысл |
|---|---|---|
| `fileName` | `String` | Имя загрузки |
| `rows` | `List<SystemeMonthlySalesRow>` | Товарные строки основного листа |
| `totals` | `List<SystemeMonthlySalesTotal>` | Итоговая строка основного листа |
| `hiddenSeasonality` | `SystemeSeasonalityReport` | Скрытый `Лист1`; структура описана в разделе 6 |

| Поле `SystemeMonthlySalesRow` | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение |
| `productName` | `String?` | A, `Номенклатура` | Название товара |
| `productCode` | `String?` | B, `Номенклатура.Код` | Код 1С |
| `supplierArticle` | `String?` | C, `Артикул` | Артикул поставщика |
| `orderMultiple` | `BigDecimal?` | D, `Кратность` | Исходная кратность из этого файла; не заменяется значением отдельного MOQ |
| `monthlySales` | `List<MonthlyValue>` | E:AK, месяц / `Количество` | Месячное количество продаж; единица отдельной колонкой не дана |
| `totalQuantity` | `BigDecimal?` | AL, `Итого / Количество` | Сохранённое количество за весь период |

`SystemeMonthlySalesTotal` содержит `sourceRow: Int`, `monthlySales: List<MonthlyValue>` и `totalQuantity: BigDecimal?` из тех же колонок строки `Итого`. В исходнике бывают нулевые кратности, отличающиеся от отдельного MOQ; импорт сохраняет это расхождение.

### 5.5. `SystemeIncomingShipmentsData` → сводная рабочая таблица

Основной лист `TDSheet`, шапка 2, данные со строки 3. Это не только партии в пути: файл содержит продажи, категории, остатки и исходные расчётные показатели.

| Поле report DTO | Kotlin-тип | Источник / смысл |
|---|---|---|
| `fileName` | `String` | Имя загрузки |
| `rows` | `List<SystemeIncomingShipmentRow>` | Товарные строки `TDSheet` |
| `hiddenSeasonality` | `SystemeSeasonalityReport` | Скрытый `Лист1`, независимо от аналогичного листа в месячных продажах |

`SystemeIncomingShipmentRow`:

| Поле | Kotlin-тип | Колонка / подпись | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Номер строки | Происхождение |
| `reportNumber` | `Int?` | A, `№` | Порядковый номер |
| `supplierArticle` | `String?` | B, `Артикул поставщика` | Артикул Systeme |
| `productCode` | `String?` | C, `Код 1с` | Код 1С |
| `productName` | `String?` | D, `Наименование` | Название товара |
| `category` | `SystemeCategory` | E, `Категория 2026` | Год и исходный код категории; не ABC-класс |
| `ssReal` | `BigDecimal?` | F, `СС реал` | Числовой показатель с неустановленными расшифровкой, валютой и единицей |
| `monthlySales` | `List<MonthlyValue>` | G:R, U:AO, месячные заголовки | История продаж в рабочем расчёте; единица в листе не подписана |
| `annualSales` | `List<SystemeAnnualProductSales>` | S:T, `Продажи 2024`, `Ср мес 2024` | Сохранённые годовые показатели; список поддерживает годы из соответствующих заголовков |
| `reportedRollingSales` | `SystemeCalculatedDecimal` | AP, `Сумма последние 12 мес` | Исходная сумма; формула текущего файла фактически захватывает 13 месяцев |
| `reportedAverageMonthlySales` | `SystemeCalculatedDecimal` | AQ, `Ср мес за последние 12 мес` | Исходное среднемесячное количество; обычный делитель 12, в одной строке 5 |
| `growthChange` | `SystemeCalculatedDecimal` | AR, `Кэф. Роста` | Относительное изменение, например −0,4 = −40%; не множитель 0,6 |
| `seasonalityChange` | `SystemeCalculatedDecimal` | AS, `Кэф. Сез-ти` | Относительное изменение сравниваемых периодов, не сезонный индекс около 1 |
| `displayQuantity` | `BigDecimal?` | AT, `Витрина` | Количество, а не boolean; встречается значение 17 |
| `tzStock` | `BigDecimal?` | AU, `Остаток ТЗ` | Количество по исходной подписи; сокращение ТЗ не расшифровывается |
| `ryskulovaDistributionStock` | `BigDecimal?` | AV, `РЦ ЕКТ Рыскулова` | Количество по этому месту хранения |
| `retailWarehouseStock` | `BigDecimal?` | AW, `Розничный склад` | Количество розничного склада |
| `stock` | `BigDecimal?` | AX, `Остаток` | Исходный общий остаток |
| `reservedStock` | `BigDecimal?` | AY, `Зарезервировано` | Зарезервированное количество |
| `availableStock` | `BigDecimal?` | AZ, `Свободный остаток` | Доступное количество; в исходных строках равно AX − AY |
| `stockCoverageMonths` | `SystemeCalculatedDecimal` | BA, `Запас` | Покрытие **в месяцах**, не количество товара; исходная формула включает заказ и путь |
| `proposedOrderQuantity` | `BigDecimal?` | BB, `Заказ` | Значение исходной колонки заказа; в предоставленном файле пусто, это не новая рекомендация backend |
| `inTransit` | `List<SystemeInTransitQuantity>` | BC, `СЭ в пути 24.09` | Количества по колонкам с подписью `СЭ в пути…` |
| `weight` | `BigDecimal?` | BH, `Вес` | В исходнике пусто; единица веса неизвестна, килограммы не предполагаются |

У количеств AT:AZ, BB и BC на этом листе нет отдельной единицы измерения. Остаток AX нельзя автоматически прибавлять к AT:AW: взаимосвязь итогового и компонентных остатков требует подтверждения.

Вложенные типы описаны один раз:

| Тип / поле | Kotlin-тип | Источник | Смысл |
|---|---|---|---|
| `SystemeCategory.year` | `Int` | Год в заголовке E | Год действия исходной категории |
| `SystemeCategory.code` | `String?` | Значение E | Код категории, например `1`, `2`, `3`, `5`, `7` |
| `SystemeAnnualProductSales.year` | `Int` | Год в заголовке `Продажи…` / `Ср мес…` | Год исходного показателя |
| `SystemeAnnualProductSales.totalSales` | `SystemeCalculatedDecimal?` | S, `Продажи 2024` | Сумма продаж года; `null`, если соответствующего заголовка нет |
| `SystemeAnnualProductSales.averageMonthlySales` | `SystemeCalculatedDecimal?` | T, `Ср мес 2024` | Среднемесячное значение; `null`, если соответствующего заголовка нет |
| `SystemeInTransitQuantity.sourceHeader` | `String` | Полный заголовок BC | Исходная подпись партии/пути |
| `SystemeInTransitQuantity.dateLabel` | `String?` | Хвост подписи, например `24.09` | Текстовая метка без придуманного года или подтверждённого ETA |
| `SystemeInTransitQuantity.quantity` | `BigDecimal?` | Ячейка BC | Количество в пути; единица отдельно не указана |

### Типы сохранённых вычисляемых значений Systeme

`SystemeCalculatedDecimal`, `SystemeCalculatedText` и `SystemeCalculatedInteger` имеют одинаковую структуру с разным типом `value`. Обёртка сохраняется и для константы; тогда `formula` равна `null`.

| Поле | Kotlin-тип | Источник / смысл |
|---|---|---|
| `sourceCell` | `String` во всех трёх типах | Адрес исходной ячейки, например `AQ485` |
| `value` | `BigDecimal?` / `String?` / `Int?` соответственно | Значение константы или сохранённый результат формулы |
| `formula` | `String?` во всех трёх типах | Текст формулы из исходной ячейки; вычисление не выполняется |
| `error` | `String?` во всех трёх типах | Ошибка Excel, если записана в ячейке; ошибки преобразования дополнительно отражаются в `ExcelIssue` |

Например, `reportedAverageMonthlySales.value` — само число, а `reportedAverageMonthlySales.formula` позволяет увидеть исходное деление на 12 или 5. Ненулевая обёртка с `value = null` отличается от отсутствующей обёртки: первая соответствует существующей ячейке/полю без корректного значения.

## 6. Systeme Electric: сезонность в трёх местах

Один набор вложенных DTO используется для отдельного `Сезонность SystemElectric 2024-2026.xlsx` и скрытых `Лист1` внутри месячных продаж и сводного файла в пути. Они сохраняются как три самостоятельных источника и не суммируются.

### `SystemeSeasonalityData` и `SystemeSeasonalityReport`

| Поле | Kotlin-тип | Где есть | Исходный блок / смысл |
|---|---|---|---|
| `fileName` | `String` | Только `SystemeSeasonalityData` | Имя отдельного файла сезонности |
| `sheetName` | `String` | Только `SystemeSeasonalityReport` | Имя вложенного листа, обычно `Лист1`; файл известен из родительского отчёта |
| `rows` | `List<SystemeSeasonalMonthRow>` | В обоих типах | B11:L22: 12 месячных строк основного блока |
| `annualSales` | `List<SystemeAnnualSalesRow>` | В обоих типах | A4:N6: исходные годовые строки |
| `annualSummaries` | `List<SystemeSeasonalAnnualSummary>` | В обоих типах | M11:O13: годовые знаменатели |
| `totals` | `List<SystemeSeasonalTotal>` | В обоих типах | Строка 23: контрольные суммы/средние |
| `corrections` | `List<SystemeSeasonalityCorrection>` | В обоих типах | Подпись `Поправка…` и соседнее значение; в скрытых листах список пуст |

В скрытых листах отсутствуют L (`СЕЗОННОСТЬ`) и поправка M15:N15 из отдельного файла. Поэтому `combinedSeasonalityIndex` для скрытых листов равен `null`. Валюта и единица агрегированных «Продаж» во всех трёх местах не заданы.

### `SystemeAnnualSalesRow`

| Поле | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 4–6 | Происхождение |
| `year` | `Int?` | A, `год` | Год ряда; при ошибке значения суммы строки сохраняются с неизвестным годом |
| `monthlySales` | `List<SystemeCalendarMonthValue>` | B:M, месяцы | Агрегированные показатели, единица неизвестна; год хранится отдельно |
| `total` | `SystemeCalculatedDecimal` | N, `ИТОГО` | Исходная сумма доступных месяцев с формулой/ошибкой |

`SystemeCalendarMonthValue` содержит `month: Month` — месяц из заголовка B:M, и `value: BigDecimal?` — агрегированный показатель за этот месяц. Год находится в родительском `SystemeAnnualSalesRow.year`. В отличие от `MonthlyValue`, непустой год не требуется для сохранения месячных чисел.

### `SystemeSeasonalMonthRow` и `SystemeSeasonalYearMetrics`

| Поле `SystemeSeasonalMonthRow` | Kotlin-тип | Источник | Смысл |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 11–22 | Происхождение |
| `month` | `Month?` | Разбор B | Месяц года; при неверной подписи может быть `null` с замечанием |
| `sourceMonthLabel` | `SystemeCalculatedText` | B, `Месяц` | Исходная подпись месяца с формулой/ошибкой |
| `years` | `List<SystemeSeasonalYearMetrics>` | Группы C:E, F:H, I:K | Показатели месяца по каждому году |
| `combinedSeasonalityIndex` | `SystemeCalculatedDecimal?` | L, `СЕЗОННОСТЬ`, если колонка есть | Итоговый безразмерный коэффициент из отдельного файла |

| Поле `SystemeSeasonalYearMetrics` | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `year` | `Int` | `Продажи <год>` в заголовке C/F/I | Год группы |
| `sales` | `SystemeCalculatedDecimal` | C / F / I | Агрегированные продажи месяца; единица неизвестна |
| `seasonalityIndex` | `SystemeCalculatedDecimal` | D / G / J, `Коэф. сезонности…` | Безразмерный индекс относительно среднего месяца |
| `shareOfYear` | `SystemeCalculatedDecimal` | E / H / K, `Доля в году…` | Доля года, например 0,08 = 8% |

Эти три обёртки содержат исходные результаты Excel. Backend не исправляет их по собственной методике сезонности.

### `SystemeSeasonalAnnualSummary`

| Поле | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строки 11–13 | Происхождение |
| `year` | `SystemeCalculatedInteger` | M, `Год` | Год вместе с исходной формулой/значением |
| `averageMonthlySales` | `SystemeCalculatedDecimal` | N, `Среднемес.` | Среднее заполненных месяцев; единица неизвестна |
| `totalSales` | `SystemeCalculatedDecimal` | O, `Итого за год` | Итог доступных месяцев; та же неизвестная единица |

### `SystemeSeasonalityCorrection`

| Поле | Kotlin-тип | Источник | Смысл / единица |
|---|---|---|---|
| `sourceRow` | `Int` | Строка 15 отдельного файла | Происхождение |
| `sourceLabel` | `String` | M15, подпись `Поправка…` | Полный исходный текст |
| `numeratorYear` | `Int?` | Первый год в подписи отношения | Год числителя поправки |
| `denominatorYear` | `Int?` | Второй год в подписи отношения | Год знаменателя |
| `periodLabel` | `String?` | Текст в скобках подписи | Период сравнения в исходной формулировке |
| `factor` | `SystemeCalculatedDecimal` | N15, соседняя ячейка | Безразмерный множитель поправки с формулой/значением |

### `SystemeSeasonalTotal`

| Поле | Kotlin-тип | Источник / смысл |
|---|---|---|
| `sourceRow` | `Int` | Строка 23 |
| `years` | `List<SystemeSeasonalYearMetrics>` | Те же группы колонок, но теперь `sales` — сумма, `seasonalityIndex` — среднее, `shareOfYear` — сумма долей |
| `combinedSeasonalityIndex` | `SystemeCalculatedDecimal?` | L23: среднее итогового коэффициента, если L существует |

Повторное использование `SystemeSeasonalYearMetrics` для итогов не превращает итог в отдельный месяц. Различие определяется родителем `SystemeSeasonalTotal`.

## 7. Связи и различия поставщиков

| Вопрос | Правило |
|---|---|
| Чем связывать товары между отчётами? | `productCode`: `Код 1с`, `Код` и `Номенклатура.Код` — названия исходного внутреннего кода. Сохранять ведущие нули, `_` и буквенные значения |
| Для чего `supplierArticle`? | Это артикул поставщика, полезный для заказа/прайса. Он не заменяет внутренний код 1С и не гарантированно уникален |
| Название товара — ключ? | Нет. Оно может содержать примечания, пробелы и отличаться между выгрузками |
| Есть ли автоматическое объединение/дедупликация при импорте? | Нет. Отчёты и исходные строки хранятся отдельно. Последующий planning-слой сопоставляет коды и проверяет дубликаты по отдельным правилам расчёта |
| MOQ одинаков для обоих? | Нет. IEK `minimumShipmentQuantity` — минимальная отгрузка. Systeme `orderMultiple` — кратность |
| Исторические остатки одинаковы? | IEK явно содержит остаток на начало месяца; у Systeme момент месячного среза не определён |
| Есть ли текущий свободный остаток? | У Systeme — `availableStock` из TDSheet. У IEK нет равнозначного поля в предоставленных отчётах |
| Как различаются поставки в пути? | IEK хранит отдельные партии с документом и `expectedBy`; Systeme сохраняет количества и неполную текстовую метку даты |
| Категория — это ABC/XYZ? | Нет. `SystemeCategory.code` — исходная категория без расшифровки; вычисляемых ABC/XYZ здесь нет |
| Можно сложить все источники продаж? | Нет. Детальные операции, месячная выгрузка и месяцы TDSheet пересекаются; их сумма удвоит спрос |
| Можно складывать остаток и все складские компоненты? | Нет без подтверждения, что это независимые количества. Общий остаток может уже включать компоненты |
| Все числа имеют единицу? | Нет. Поля `unit` есть только в отдельных отчётах. Валюта сезонности, смысл/валюта `СС реал` и единица веса не установлены |

Импорт не добавляет ID клиента, цены продажи, сроки поставки из справочника, страховой запас или рекомендуемое количество заказа. `proposedOrderQuantity` — чтение исходной пустой/заполненной ячейки BB. Новое рекомендованное количество возвращает planning endpoint в отдельном поле `items[].order_qty`; значение BB в загруженном DTO при этом не меняется.

## 8. HTTP-ответ и доступ к данным из Kotlin

`POST /api/excel` возвращает `UploadResponse`, а не всё дерево `ExcelUpload`:

| Переданные группы | Требуемые поля | Результат успешного сохранения |
|---|---|---|
| Только IEK | Все шесть `iek…` | `iek` заполнен, `systeme = null` |
| Только Systeme | Все шесть `systeme…` | `iek = null`, `systeme` заполнен |
| Оба поставщика | Все 12 полей | Обе группы заполнены |

Нулевой набор, неполная группа, дублирующее или лишнее файловое поле дают `400`. Например, шесть IEK и один Systeme не являются допустимым запросом: требуется оставшиеся пять Systeme либо полное отсутствие этой группы. В каждом переданном поле должен быть один непустой `.xlsx`. Проверка и разбор всего запроса завершаются до замены снимка; при ошибке прежние данные сохраняются целиком.

| Поле | Kotlin-тип | Смысл |
|---|---|---|
| `iekFiles` | `Int` | 6, если передан IEK; иначе 0 |
| `systemeFiles` | `Int` | 6, если передан Systeme; иначе 0 |
| `totalFiles` | `Int` | 6 для одного поставщика или 12 для обоих |
| `recordCounts` | `Map<String, Int>` | Только переданные отчёты: ключ — имя multipart-поля; значение — число основных строк отчёта. Ключи отсутствующей группы не возвращаются |
| `issues` | `List<ExcelIssue>` | Замечания к данным; их нужно показывать и при успешном ответе |

Для десяти товарных отчётов счётчик равен размеру `rows`. Для `iekSeasonality` это размер `yearlySeasonality`, для `systemeSeasonality` — размер `rows`: в предоставленных файлах по **12 месячных строк основного блока**. Вспомогательные годовые строки, знаменатели, нормализация, скрытые отчёты и итоги к этим счётчикам не прибавляются. Число строк — не обязательно число уникальных товаров.

Примеры доступа внутри backend после успешной загрузки. Группа поставщика может быть `null`, поэтому примеры используют безопасный доступ:

```kotlin
// История продаж первого товара IEK: List<MonthlyValue>?
val iekMonthlySales = store.currentUpload
    ?.iek?.monthlySales?.rows?.firstOrNull()?.sales

// Конкретный месяц — с явным годом, а не по позиции в массиве.
val september2026 = iekMonthlySales
    ?.firstOrNull { it.month == java.time.YearMonth.of(2026, 9) }
    ?.value

// Свободный остаток и сохранённое среднее из сводной таблицы Systeme.
val systemeRow = store.currentUpload
    ?.systeme?.incomingShipments?.rows?.firstOrNull()
val available = systemeRow?.availableStock
val reportedAverage = systemeRow?.reportedAverageMonthlySales?.value

// Партия IEK: количество связывается с её документом по sourceColumn.
val incoming = store.currentUpload?.iek?.incomingShipments
val firstQuantity = incoming?.rows?.firstOrNull()?.quantities?.firstOrNull()
val shipment = incoming?.shipments
    ?.firstOrNull { it.sourceColumn == firstQuantity?.sourceColumn }
val expectedBy = shipment?.expectedBy
```

Это внутренние Kotlin-модели исходных отчётов. `GET /api/orders` возвращает отдельный DTO рекомендаций на их основе, а не все импортированные строки. Контракт и обязательные параметры расчёта — в [order_calculation.md](order_calculation.md).

## 9. Проверка на предоставленных файлах

Полный `POST /api/excel` проверен через MockMvc на всех 12 исходных `.xlsx`. Получены следующие `recordCounts`:

| Вид отчёта | IEK | Systeme Electric |
|---|---:|---:|
| MOQ | 1938 | 554 |
| Динамика продаж | 171603 | 77312 |
| Ежемесячные остатки | 2853 | 701 |
| Ежемесячные продажи | 2463 | 554 |
| Товар в пути / сводная таблица | 2641 | 497 |
| Основные месячные строки сезонности | 12 | 12 |

При совместной загрузке этих исходников импорт возвращает 15 замечаний: сохранённые `#N/A` в MOQ IEK, для которых `minimumShipmentQuantity = null`. Пустые ячейки выгрузок 1С, помеченные в XML как error-type, но не имеющие значения, читаются как `null` без придуманной ошибки. Встроенный справочный external-link cache прайса IEK отдельно не импортируется. Таблица выше описывает проверенные исходники, а не фиксированные ожидаемые размеры любого нового файла. При загрузке одного поставщика `recordCounts` содержит только его шесть отчётов.
