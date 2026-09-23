package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Month
import java.time.YearMonth

/** Converts the imported IEK reports to planning inputs without forecasting or changing the upload. */
@Component
class IekOrderSource {
    fun build(data: IekData): PlanningSource {
        val warnings = linkedSetOf<String>()
        val products = linkedMapOf<String, ProductRows>()

        fun <T> collect(rows: List<T>, source: String, code: (T) -> String?, append: (ProductRows, T) -> Unit) {
            var missingCode = 0
            rows.forEach { row ->
                val key = text(code(row))
                if (key == null) missingCode++
                else append(products.getOrPut(key) { ProductRows() }, row)
            }
            if (missingCode > 0) warnings.add("IEK: $source — $missingCode строк без кода 1С не сопоставлены с товарами.")
        }

        collect(data.moq.rows, "MOQ", { it.productCode }) { product, row -> product.moq.add(row) }
        collect(data.monthlyStocks.rows, "остатки", { it.productCode }) { product, row -> product.stocks.add(row) }
        collect(data.monthlySales.rows, "месячные продажи", { it.productCode }) { product, row -> product.monthlySales.add(row) }
        collect(data.incomingShipments.rows, "товар в пути", { it.productCode }) { product, row -> product.incoming.add(row) }
        collect(data.salesDynamics.rows, "динамика продаж", { it.productCode }) { product, row -> product.sales.add(row) }

        val stockMonth = (data.monthlyStocks.rows.asSequence().flatMap { it.openingStocks.asSequence() } +
            data.monthlyStocks.totals.asSequence().flatMap { it.openingStocks.asSequence() })
            .map { it.month }.maxOrNull()
        val stockWarning = if (stockMonth != null) {
            "IEK: используются остатки на начало $stockMonth, а не текущие свободные остатки; расчёт пополнения является оценкой."
        } else {
            "IEK: период начальных остатков не определён; текущие свободные остатки отсутствуют."
        }
        warnings.add(stockWarning)
        val dataThrough = data.salesDynamics.rows.mapNotNull { it.occurredAt?.toLocalDate() }.maxOrNull()
        if (dataThrough == null) warnings.add("IEK: в динамике продаж нет даты, по которой можно определить актуальность данных.")

        return PlanningSource(
            supplierId = "iek",
            supplierName = "IEK",
            dataThrough = dataThrough,
            products = products.entries.sortedBy { it.key }.map { (code, rows) ->
                product(code, rows, data.incomingShipments.shipments, stockMonth, stockWarning)
            },
            seasonality = seasonality(data.seasonality, warnings),
            warnings = warnings.toList(),
        )
    }

    private fun product(
        code: String,
        rows: ProductRows,
        shipmentColumns: List<IekShipmentColumn>,
        stockMonth: YearMonth?,
        stockWarning: String,
    ): PlanningProduct {
        val problems = linkedSetOf<String>()
        val warnings = linkedSetOf(stockWarning)
        val articles = (rows.moq.map { it.supplierArticle } + rows.incoming.map { it.supplierArticle })
            .mapNotNull(::text).distinct()
        if (articles.size > 1) problems.add("Для кода 1С указаны разные артикулы поставщика: ${articles.joinToString()}.")
        val names = (rows.stocks.map { it.productName } + rows.monthlySales.map { it.productName } +
            rows.moq.map { it.productName } + rows.incoming.map { it.productName } + rows.sales.map { it.productName })
            .mapNotNull(::text)
        val units = (rows.stocks.map { it.unit } + rows.sales.map { it.unit })
            .mapNotNull(::text).distinctBy { it.lowercase() }
        if (units.size > 1) problems.add("Несовместимые единицы измерения: ${units.joinToString()}; количества не объединены для расчёта.")
        if (units.isEmpty()) warnings.add("Единица измерения товара не указана.")

        if (names.any(::requiresReelConversion)) {
            problems.add("Закупка в бухтах и учёт в метрах требуют коэффициента пересчёта, которого нет в данных.")
        }

        val stockHistory = monthlySeries(rows.stocks.map { it.openingStocks }, "остатков", problems)
        val monthlySales = monthlySeries(rows.monthlySales.map { it.sales }, "месячных продаж", problems)
        resolveQuantity(rows.stocks.map { it.periodOpeningStock }, "итога начальных остатков", problems)
        resolveQuantity(rows.monthlySales.map { it.periodTotal }, "итога месячных продаж", problems)
        val minimumShipment = resolveQuantity(rows.moq.map { it.minimumShipmentQuantity }, "минимальной отгрузки MOQ", problems)
        if (minimumShipment != null && minimumShipment.signum() <= 0) {
            problems.add("Минимальная отгрузка MOQ должна быть положительной.")
        }
        if (minimumShipment == null && rows.moq.isNotEmpty()) warnings.add("Минимальная отгрузка MOQ отсутствует или неоднозначна.")
        if (rows.incoming.isEmpty()) warnings.add("В файле товара в пути нет строки этого кода; поставки не подтверждены.")

        return PlanningProduct(
            code = code,
            article = articles.singleOrNull(),
            name = names.firstOrNull(),
            unit = units.singleOrNull(),
            monthlySales = monthlySales,
            stockHistory = stockHistory,
            // Use the shared report month, including a null cell. Never take the last non-null stock.
            stock = stockHistory.firstOrNull { it.month == stockMonth }?.value,
            stockBasis = "MONTH_OPENING",
            stockMonth = stockMonth,
            incoming = incoming(rows.incoming, shipmentColumns, problems),
            minimumShipment = minimumShipment,
            // IEK's minimum shipment is not evidence of an order multiple.
            orderMultiple = null,
            sales = rows.sales.map {
                PlanningSale(it.occurredAt?.toLocalDate(), it.documentNumber, it.document, it.quantity)
            },
            problems = problems.toList(),
            warnings = warnings.toList(),
        )
    }

    private fun monthlySeries(
        rows: List<List<MonthlyValue>>,
        source: String,
        problems: MutableSet<String>,
    ): List<MonthlyValue> {
        if (rows.isEmpty()) return emptyList()
        val byMonth = rows.map { values ->
            values.groupBy { it.month }.mapValues { (month, entries) ->
                resolveQuantity(entries.map { it.value }, "$source за $month", problems)
            }
        }
        val signatures = byMonth.map { values -> values.mapValues { it.value?.stripTrailingZeros() } }
        if (signatures.distinct().size > 1) problems.add("Несогласованные дубликаты строк $source; их значения не суммируются.")
        return byMonth.flatMap { it.keys }.distinct().sorted().map { month ->
            MonthlyValue(month, resolveQuantity(byMonth.map { it[month] }, "$source за $month", problems))
        }
    }

    private fun incoming(
        rows: List<IekIncomingShipmentRow>,
        columns: List<IekShipmentColumn>,
        problems: MutableSet<String>,
    ): List<PlanningIncoming> {
        if (rows.isEmpty()) return emptyList()
        val rowQuantities = rows.map { row ->
            row.quantities.groupBy { it.sourceColumn }.mapValues { (column, quantities) ->
                resolveQuantity(quantities.map { it.quantity }, "товара в пути, колонка $column", problems)
            }
        }
        val signatures = rowQuantities.map { values -> values.mapValues { it.value?.stripTrailingZeros() } }
        if (signatures.distinct().size > 1) problems.add("Несогласованные дубликаты строк товара в пути; партии не суммируются.")
        val metadata = columns.groupBy { it.sourceColumn }
        val sourceColumns = (columns.map { it.sourceColumn } + rowQuantities.flatMap { it.keys }).distinct()
        return sourceColumns.map { column ->
            val definitions = metadata[column].orEmpty()
            val metadataConflict = definitions.map { listOf(it.orderNumber, it.orderDate, it.expectedBy, it.documentPrefix) }.distinct().size > 1
            if (metadataConflict) problems.add("Разные сведения о партии в колонке $column файла товара в пути.")
            if (definitions.isEmpty()) problems.add("Для количества в колонке $column отсутствует заголовок партии.")
            val definition = definitions.firstOrNull()
            PlanningIncoming(
                quantity = resolveQuantity(rowQuantities.map { it[column] }, "товара в пути, колонка $column", problems),
                expectedBy = definition?.expectedBy.takeUnless { metadataConflict },
                reference = definition?.sourceHeader ?: definition?.orderNumber ?: column,
            )
        }
    }

    private fun resolveQuantity(values: List<BigDecimal?>, source: String, problems: MutableSet<String>): BigDecimal? {
        val unique = values.map { it?.stripTrailingZeros() }.distinct()
        if (unique.size > 1) {
            problems.add("Конфликтующие значения $source; требуется проверка источника.")
            return null
        }
        return values.firstOrNull()
    }

    private fun seasonality(data: IekSeasonalityData, warnings: MutableSet<String>): Map<Month, BigDecimal> {
        val normalized = data.normalizedSeasonality.groupBy { it.month }
        val yearly = data.yearlySeasonality.groupBy { it.month }
        if ((normalized.keys + yearly.keys).any { it == null || it !in 1..12 }) {
            warnings.add("IEK: строки сезонности без корректного номера месяца не использованы.")
        }
        val result = linkedMapOf<Month, BigDecimal>()
        for (month in Month.entries) {
            val preferred = seasonalityValue(
                normalized[month.value].orEmpty().map { it.normalizedCoefficient }, month, "нормализованной сезонности", warnings,
            )
            val fallback = if (preferred == null) seasonalityValue(
                yearly[month.value].orEmpty().map { it.normalizedCoefficient }, month, "годовой таблицы сезонности", warnings,
            ) else null
            val coefficient = preferred ?: fallback
            if (coefficient != null) result[month] = coefficient
        }
        return result
    }

    private fun seasonalityValue(
        values: List<BigDecimal?>,
        month: Month,
        source: String,
        warnings: MutableSet<String>,
    ): BigDecimal? {
        val unique = values.filterNotNull().map { it.stripTrailingZeros() }.distinct()
        if (unique.size > 1) {
            warnings.add("IEK: разные коэффициенты $source за месяц ${month.value}; неоднозначный коэффициент не использован.")
            return null
        }
        val value = unique.singleOrNull() ?: return null
        if (value.signum() <= 0) {
            warnings.add("IEK: неположительный коэффициент $source за месяц ${month.value} не использован.")
            return null
        }
        return value
    }

    private fun requiresReelConversion(name: String): Boolean {
        val normalized = name.lowercase().replace('ё', 'е')
        return normalized.contains("бухт") && (normalized.contains("метраж") || normalized.contains("метр")) &&
            (normalized.contains("закуп") || normalized.contains("учет") || normalized.contains("садят"))
    }

    private fun text(value: String?): String? = value?.replace('\u00a0', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private class ProductRows {
        val moq = mutableListOf<IekMoqRow>()
        val stocks = mutableListOf<IekMonthlyStockRow>()
        val monthlySales = mutableListOf<IekMonthlySalesRow>()
        val incoming = mutableListOf<IekIncomingShipmentRow>()
        val sales = mutableListOf<IekSalesDynamicsRow>()
    }
}
