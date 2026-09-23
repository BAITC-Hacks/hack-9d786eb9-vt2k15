package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.time.Month

/** Joins source reports without calculating forecasts or mutating the uploaded snapshot. */
@Component
class SystemeOrderSource {
    fun build(data: SystemeData): PlanningSource {
        val sourceWarnings = mutableListOf<String>()
        val moq = index(data.moq.rows, "MOQ", sourceWarnings, { it.productCode }) {
            Record(article = it.supplierArticle, name = it.productName, multiple = it.orderMultiple)
        }
        val stocks = index(data.monthlyStocks.rows, "Ежемесячные остатки", sourceWarnings, { it.productCode }) {
            Record(name = it.productName, unit = it.unit, stockHistory = it.monthlyStocks)
        }
        val monthly = index(data.monthlySales.rows, "Ежемесячные продажи", sourceWarnings, { it.productCode }) {
            Record(article = it.supplierArticle, name = it.productName, monthlySales = it.monthlySales)
        }
        val snapshots = index(data.incomingShipments.rows, "Товар в пути", sourceWarnings, { it.productCode }) {
            Record(
                article = it.supplierArticle, name = it.productName, category = it.category.code,
                monthlySales = it.monthlySales, stock = it.stock, reservedStock = it.reservedStock,
                availableStock = it.availableStock, incoming = it.inTransit, growth = it.growthChange.value,
            )
        }
        val sales = data.salesDynamics.rows.filter { clean(it.productCode) != null }.groupBy { clean(it.productCode)!! }
        val unlinkedSales = data.salesDynamics.rows.count { clean(it.productCode) == null }
        if (unlinkedSales > 0) sourceWarnings += "Динамика продаж: $unlinkedSales строк без кода товара не удалось связать с товаром."
        val through = data.salesDynamics.rows.mapNotNull { it.documentDateTime?.toLocalDate() }.maxOrNull()
        if (through == null) sourceWarnings += "В динамике продаж отсутствует дата, определяющая конец доступных данных."

        val codes = (moq.keys + stocks.keys + monthly.keys + snapshots.keys + sales.keys).toSortedSet()
        val products = codes.map { code ->
            val problems = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val terms = pick(moq[code], "MOQ", problems, warnings)
            val history = pick(stocks[code], "Ежемесячные остатки", problems, warnings)
            val months = pick(monthly[code], "Ежемесячные продажи", problems, warnings)
            val snapshot = pick(snapshots[code], "Товар в пути", problems, warnings)
            val transactions = sales[code].orEmpty()

            val unitValues = (stocks[code].orEmpty().map { it.unit } + transactions.map { it.unit })
                .mapNotNull(::clean).distinctBy { it.lowercase() }
            if (unitValues.size > 1) problems += "Несовместимые единицы количества: ${unitValues.joinToString()}."
            if (unitValues.isEmpty()) warnings += "Единица количества неизвестна."
            if (transactions.isEmpty()) warnings += "Для товара нет строк динамики продаж."
            if (transactions.any { it.documentDateTime == null }) warnings += "В динамике есть операции без известной даты."
            if (transactions.any { it.quantity == null }) warnings += "В динамике есть операции без известного количества."

            val stock = when {
                snapshot?.availableStock != null -> snapshot.availableStock
                snapshot?.stock != null && snapshot.reservedStock != null -> {
                    warnings += "Свободный остаток отсутствует; вычислен как Остаток − Зарезервировано из того же снимка."
                    snapshot.stock.subtract(snapshot.reservedStock)
                }
                else -> {
                    warnings += "Текущий свободный остаток неизвестен; исторический месячный остаток его не заменяет."
                    null
                }
            }
            val incoming = if (snapshot == null || snapshot.incoming.isEmpty()) {
                warnings += "Сведения о количестве товара в пути отсутствуют; нулевое поступление не подтверждено."
                listOf(PlanningIncoming(null, null, null))
            } else {
                if (snapshot.incoming.any { it.quantity == null }) warnings += "Количество хотя бы одной партии в пути неизвестно."
                warnings += "Срок поступления товара в пути не подтверждён полной датой; expectedBy неизвестен."
                snapshot.incoming.map { PlanningIncoming(it.quantity, null, clean(it.dateLabel)) }
            }
            if (terms?.multiple == null) warnings += "Кратность отсутствует в отдельном MOQ; округление по кратности не применяется."
            if (terms?.multiple != null && terms.multiple.signum() <= 0) problems += "Кратность отдельного MOQ должна быть положительной."
            if (months == null && snapshot != null) warnings += "Месячные продажи взяты из рабочего снимка: товар отсутствует в отдельном отчёте продаж."
            if (months == null && snapshot == null) warnings += "Месячная история продаж отсутствует."
            if (history == null) warnings += "Месячная история остатков отсутствует."
            if (snapshot?.growth == null) warnings += "Исходное изменение роста Systeme отсутствует."

            val article = firstText(snapshot?.article, terms?.article, months?.article)
            val name = firstText(snapshot?.name, months?.name, terms?.name, history?.name, transactions.firstNotNullOfOrNull { clean(it.productName) })
            PlanningProduct(
                code = code,
                article = article,
                name = name,
                unit = unitValues.firstOrNull(),
                category = clean(snapshot?.category),
                monthlySales = uniqueMonths((months ?: snapshot)?.monthlySales.orEmpty(), "Месячные продажи", problems, warnings),
                stockHistory = uniqueMonths(history?.stockHistory.orEmpty(), "Месячные остатки", problems, warnings),
                // Document numbers are reused across years; they are not transaction IDs.
                sales = transactions.map { PlanningSale(it.documentDateTime?.toLocalDate(), it.documentNumber, it.documentDescription, it.quantity) },
                stock = stock,
                stockBasis = "AVAILABLE_STOCK",
                stockMonth = null,
                incoming = incoming,
                minimumShipment = null,
                orderMultiple = terms?.multiple,
                sourceGrowthChange = snapshot?.growth,
                problems = problems.distinct(),
                warnings = warnings.distinct(),
            )
        }
        val seasonality = seasonality(data.seasonality, sourceWarnings)
        return PlanningSource("systeme", "Systeme Electric", through, products, seasonality, sourceWarnings.distinct())
    }

    private fun seasonality(data: SystemeSeasonalityData, warnings: MutableList<String>): Map<Month, BigDecimal> {
        val unknownMonths = data.rows.count { it.month == null }
        if (unknownMonths > 0) warnings += "Сезонность: $unknownMonths строк без определённого месяца не используются."
        return buildMap {
            for ((month, rows) in data.rows.filter { it.month != null }.groupBy { it.month!! }) {
                val combined = rows.mapNotNull { it.combinedSeasonalityIndex?.value }.map(::canonical).distinct()
                if (combined.size > 1) {
                    warnings += "Сезонность $month: противоречивые итоговые коэффициенты, месяц не используется."
                    continue
                }
                val value = combined.singleOrNull()
                if (value != null && value.signum() > 0) {
                    put(month, value)
                    continue
                }
                val yearly = rows.flatMap { it.years }.groupBy { it.year }.mapNotNull { (year, values) ->
                    val coefficients = values.mapNotNull { it.seasonalityIndex.value }.map(::canonical).distinct()
                    if (coefficients.size > 1) {
                        warnings += "Сезонность $month, $year: противоречивые годовые коэффициенты не используются."
                        null
                    } else coefficients.singleOrNull()?.takeIf { it.signum() > 0 }
                }
                if (yearly.isEmpty()) {
                    warnings += "Сезонность $month: нет положительного итогового или годового коэффициента."
                } else {
                    put(month, yearly.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(yearly.size), MathContext.DECIMAL128))
                    warnings += "Сезонность $month: вместо отсутствующего/неположительного итогового коэффициента использовано среднее положительных годовых коэффициентов."
                }
            }
        }
    }

    private fun <T> index(
        rows: List<T>, report: String, warnings: MutableList<String>, code: (T) -> String?, record: (T) -> Record,
    ): Map<String, List<Record>> {
        val invalid = rows.count { clean(code(it)) == null }
        if (invalid > 0) warnings += "$report: $invalid строк без кода товара не удалось связать с товаром."
        return rows.filter { clean(code(it)) != null }.groupBy { clean(code(it))!! }.mapValues { (_, items) -> items.map(record) }
    }

    private fun pick(rows: List<Record>?, label: String, problems: MutableList<String>, warnings: MutableList<String>): Record? {
        if (rows.isNullOrEmpty()) return null
        if (rows.size > 1) {
            if (rows.map { it.normalized() }.distinct().size > 1) problems += "$label: несколько противоречивых строк для одного кода товара."
            else warnings += "$label: ${rows.size} совпадающих строк одного товара объединены без суммирования."
        }
        return rows.first()
    }

    private fun uniqueMonths(values: List<MonthlyValue>, label: String, problems: MutableList<String>, warnings: MutableList<String>): List<MonthlyValue> =
        values.groupBy { it.month }.toSortedMap().map { (month, entries) ->
            if (entries.size > 1) {
                if (entries.map { it.value?.let(::canonical) }.distinct().size > 1) problems += "$label: противоречивые значения за $month."
                else warnings += "$label: повторяющиеся значения за $month объединены без суммирования."
            }
            entries.first()
        }

    /** Only fields used by planning take part in duplicate comparison; source row numbers do not. */
    private data class Record(
        val article: String? = null,
        val name: String? = null,
        val unit: String? = null,
        val category: String? = null,
        val monthlySales: List<MonthlyValue> = emptyList(),
        val stockHistory: List<MonthlyValue> = emptyList(),
        val stock: BigDecimal? = null,
        val reservedStock: BigDecimal? = null,
        val availableStock: BigDecimal? = null,
        val incoming: List<SystemeInTransitQuantity> = emptyList(),
        val multiple: BigDecimal? = null,
        val growth: BigDecimal? = null,
    ) {
        fun normalized() = copy(
            article = clean(article), name = clean(name), unit = clean(unit)?.lowercase(), category = clean(category),
            monthlySales = monthlySales.map { it.copy(value = it.value?.let(::canonical)) }.sortedBy { it.month },
            stockHistory = stockHistory.map { it.copy(value = it.value?.let(::canonical)) }.sortedBy { it.month },
            stock = stock?.let(::canonical), reservedStock = reservedStock?.let(::canonical), availableStock = availableStock?.let(::canonical),
            incoming = incoming.map { it.copy(sourceHeader = it.sourceHeader.trim(), dateLabel = clean(it.dateLabel), quantity = it.quantity?.let(::canonical)) }
                .sortedWith(compareBy({ it.sourceHeader }, { it.dateLabel })),
            multiple = multiple?.let(::canonical), growth = growth?.let(::canonical),
        )
    }

    private companion object {
        fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
        fun firstText(vararg values: String?): String? = values.firstNotNullOfOrNull(::clean)
        fun canonical(value: BigDecimal): BigDecimal = value.stripTrailingZeros()
    }
}
