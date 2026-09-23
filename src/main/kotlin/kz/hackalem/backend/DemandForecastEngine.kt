package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/** Transparent demand estimate; source quantities and uploaded reports remain unchanged. */
@Component
class DemandForecastEngine {
    fun forecast(
        product: PlanningProduct,
        source: PlanningSource,
        parameters: OrderPlanningParameters,
    ): DemandForecast? {
        val dataThrough = source.dataThrough ?: return null
        require(parameters.historyMonths > 0) { "Период истории должен быть положительным." }
        require(parameters.leadDays >= 0 && parameters.reviewDays >= 0 && parameters.horizonDays > 0) {
            "Горизонт прогноза должен быть положительным."
        }
        val extraGrowth = ONE.add(parameters.forecastGrowthPercent.divide(HUNDRED, MC))
        require(extraGrowth.signum() >= 0) { "Прогнозный прирост не может быть ниже -100%." }

        val warnings = linkedSetOf<String>()
        val currentMonth = YearMonth.from(dataThrough)
        val through = if (dataThrough == currentMonth.atEndOfMonth()) currentMonth else currentMonth.minusMonths(1)
        val from = through.minusMonths(parameters.historyMonths.toLong() - 1)
        val months = (0 until parameters.historyMonths).map { from.plusMonths(it.toLong()) }
        if (dataThrough != currentMonth.atEndOfMonth()) {
            warnings += "Неполный месяц $currentMonth исключён из истории; данные на $dataThrough."
        }

        val monthly = if (product.monthlySales.isNotEmpty()) {
            uniqueMonths(product.monthlySales, "продаж", warnings)
        } else {
            fallbackMonthlySales(product, source, months, warnings) ?: return null
        }
        val knownMonths = months.filter { monthly[it] != null }
        if (knownMonths.size < MIN_HISTORY_MONTHS) return null
        val unknownCount = months.size - knownMonths.size
        if (unknownCount > 0) warnings += "$unknownCount мес. с неизвестными продажами исключены из расчёта, а не заменены нулём."

        val seasonality = normalizedSeasonality(source.seasonality, warnings)
        val outliers = outliersByMonth(product.sales, from, through, warnings)
        var excluded = ZERO
        val observations = knownMonths.map { month ->
            val raw = requireNotNull(monthly[month])
            var quantity = raw.max(ZERO)
            if (raw.signum() < 0) warnings += "Отрицательные продажи за $month приняты за 0 для прогноза; исходное значение $raw сохранено в отчёте."
            val outlierQuantity = outliers[month] ?: ZERO
            if (outlierQuantity.signum() > 0) {
                if (outlierQuantity <= quantity) {
                    quantity = quantity.subtract(outlierQuantity)
                    excluded = excluded.add(outlierQuantity)
                } else {
                    warnings += "Несогласованность за $month: разовые документы ($outlierQuantity) превышают месячные продажи ($quantity); вычитание не выполнено."
                }
            }
            Observation(month, quantity, seasonality.getValue(month.month))
        }
        outliers.keys.filter { monthly[it] == null }.forEach {
            warnings += "Разовые документы за $it не вычтены: месячные продажи неизвестны."
        }

        val stocks = uniqueMonths(product.stockHistory, "остатков", warnings)
        val possibleStockouts = observations.filter { stocks[it.month]?.signum() == 0 }
        val reference = observations.filter { stocks[it.month]?.signum() != 0 }
        var stockoutAdjustment = ZERO
        if (possibleStockouts.isNotEmpty()) {
            warnings += "Нулевой месячный остаток использован как признак возможного stockout; точные дни отсутствия товара неизвестны."
            if (reference.isNotEmpty()) {
                val referenceDaily = weightedDaily(reference)
                possibleStockouts.forEach { observation ->
                    val restored = referenceDaily.multiply(observation.days, MC).multiply(observation.seasonalIndex, MC)
                    if (restored > observation.quantity) {
                        stockoutAdjustment = stockoutAdjustment.add(restored.subtract(observation.quantity))
                        observation.quantity = restored
                    }
                }
            } else {
                warnings += "Спрос при возможном stockout не восстановлен: нет известных месяцев без нулевого остатка для сравнения."
            }
        }

        val baseDaily = weightedDaily(observations)
        val trend = sustainedTrend(observations, through, warnings)
        val sourceGrowth = product.sourceGrowthChange?.let {
            clampGrowth(ONE.add(it), "Исходный коэффициент роста", warnings)
        } ?: ONE
        val seasonalDays = (1..parameters.horizonDays).fold(ZERO) { sum, day ->
            sum.add(seasonality.getValue(dataThrough.plusDays(day.toLong()).month))
        }
        val seasonalFactor = seasonalDays.divide(parameters.horizonDays.toBigDecimal(), MC)
        val forecast = baseDaily.multiply(trend, MC).multiply(sourceGrowth, MC)
            .multiply(extraGrowth, MC).multiply(seasonalDays, MC).max(ZERO)

        return DemandForecast(
            historyFrom = from,
            historyThrough = through,
            baseDailyDemand = output(baseDaily),
            trendFactor = output(trend),
            sourceGrowthFactor = output(sourceGrowth),
            seasonalFactor = output(seasonalFactor),
            forecastQuantity = output(forecast),
            stockoutAdjustment = output(stockoutAdjustment),
            excludedOutlierQuantity = output(excluded),
            warnings = warnings.toList(),
        )
    }

    private fun uniqueMonths(
        values: List<MonthlyValue>,
        label: String,
        warnings: MutableSet<String>,
    ): Map<YearMonth, BigDecimal?> = values.groupBy { it.month }.mapValues { (month, rows) ->
        val distinct = rows.map { it.value?.stripTrailingZeros() }.distinct()
        if (distinct.size > 1) {
            warnings += "Противоречивые значения $label за $month исключены из расчёта."
            null
        } else {
            if (rows.size > 1) warnings += "Повторные одинаковые значения $label за $month учтены один раз."
            distinct.single()
        }
    }

    private fun fallbackMonthlySales(
        product: PlanningProduct,
        source: PlanningSource,
        months: List<YearMonth>,
        warnings: MutableSet<String>,
    ): Map<YearMonth, BigDecimal?>? {
        val sales = product.sales.filter { it.date != null && isExpense(it) && YearMonth.from(it.date) in months }
        if (sales.none { it.quantity != null }) return null
        // A missing SKU monthly report may still have operations in the common export interval.
        val commonFirstMonth = source.products.asSequence().flatMap { it.sales.asSequence() }
            .filter { it.date != null && isExpense(it) }
            .map { YearMonth.from(it.date) }.minOrNull()
            ?: sales.minOf { YearMonth.from(it.date) }
        val groups = sales.groupBy { YearMonth.from(it.date) }
        warnings += "Месячного отчёта для товара нет: история восстановлена из расходных накладных."
        warnings += "Внутри общего периода выгрузки отсутствие расходных накладных принято за нулевые продажи; полнота выгрузки требует проверки."
        return months.filter { it >= commonFirstMonth }.associateWith { month ->
            val documents = groups[month].orEmpty()
            if (documents.any { it.quantity == null }) {
                warnings += "Динамика продаж за $month содержит неизвестное количество; месяц исключён."
                null
            } else documents.fold(ZERO) { sum, sale -> sum.add(requireNotNull(sale.quantity)) }
        }
    }

    private fun normalizedSeasonality(
        input: Map<Month, BigDecimal>,
        warnings: MutableSet<String>,
    ): Map<Month, BigDecimal> {
        val positive = input.filterValues { it.signum() > 0 }
        if (positive.size < 12) {
            warnings += "Отсутствующие или неположительные сезонные коэффициенты заменены нейтральным индексом 1."
        }
        if (positive.isEmpty()) return Month.entries.associateWith { ONE }
        val average = positive.values.fold(ZERO, BigDecimal::add).divide(positive.size.toBigDecimal(), MC)
        return Month.entries.associateWith { positive[it]?.divide(average, MC) ?: ONE }
    }

    private fun outliersByMonth(
        sales: List<PlanningSale>,
        from: YearMonth,
        through: YearMonth,
        warnings: MutableSet<String>,
    ): Map<YearMonth, BigDecimal> {
        val candidates = sales.filter {
            it.date != null && YearMonth.from(it.date) in from..through && isExpense(it) && it.quantity?.signum() == 1
        }
        if (candidates.isEmpty()) {
            warnings += "Нет положительных расходных накладных для проверки разовых крупных заказов."
            return emptyMap()
        }
        warnings += "ID клиента отсутствует: крупные заказы проверяются по документам, а не по покупателю."
        if (candidates.any { it.documentNumber.isNullOrBlank() }) {
            warnings += "Документы без номера исключены из детектора разовых крупных заказов."
        }
        val orders = candidates.filter { !it.documentNumber.isNullOrBlank() }
            .groupBy { DocumentKey(requireNotNull(it.date), requireNotNull(it.documentNumber).trim()) }
            .mapValues { (_, lines) -> lines.fold(ZERO) { sum, line -> sum.add(requireNotNull(line.quantity)) } }
        if (orders.size < MIN_ORDERS) {
            warnings += "Меньше $MIN_ORDERS расходных документов: недостаточно наблюдений для удаления разовых крупных заказов."
            return emptyMap()
        }
        val quantities = orders.values.sorted()
        val median = quantile(quantities, BigDecimal("0.5"))
        val lower = quantile(quantities, BigDecimal("0.25"))
        val upper = quantile(quantities, BigDecimal("0.75"))
        val threshold = median.multiply(THREE).max(upper.add(upper.subtract(lower).multiply(BigDecimal("1.5"))))
        return orders.filterValues { it > threshold }.entries.groupBy { YearMonth.from(it.key.date) }
            .mapValues { (_, ordersInMonth) -> ordersInMonth.fold(ZERO) { sum, order -> sum.add(order.value) } }
    }

    private fun sustainedTrend(
        observations: List<Observation>,
        through: YearMonth,
        warnings: MutableSet<String>,
    ): BigDecimal {
        val byMonth = observations.associateBy { it.month }
        val lastSix = (5 downTo 0).map { byMonth[through.minusMonths(it.toLong())] ?: return ONE }
        val previous = lastSix.take(3).map { it.daily() }
        val recent = lastSix.takeLast(3).map { it.daily() }
        val previousMean = mean(previous)
        val previousMedian = quantile(previous.sorted(), BigDecimal("0.5"))
        if (previousMean.signum() == 0 || previousMedian.signum() == 0) {
            if (recent.any { it.signum() > 0 }) warnings += "Рост относительно нулевой базы не экстраполирован: устойчивый коэффициент определить нельзя."
            return ONE
        }
        val ratio = mean(recent).divide(previousMean, MC)
        val medianRatio = quantile(recent.sorted(), BigDecimal("0.5")).divide(previousMedian, MC)
        val risingPairs = recent.zip(previous).count { (new, old) -> new > old && new >= old.multiply(BigDecimal("1.1")) }
        val fallingPairs = recent.zip(previous).count { (new, old) -> new < old && new <= old.multiply(BigDecimal("0.9")) }
        val sustained = (ratio >= BigDecimal("1.1") && medianRatio >= BigDecimal("1.1") && risingPairs >= 2) ||
            (ratio <= BigDecimal("0.9") && medianRatio <= BigDecimal("0.9") && fallingPairs >= 2)
        return if (sustained) clampGrowth(ratio, "Устойчивый тренд", warnings) else ONE
    }

    private fun clampGrowth(value: BigDecimal, label: String, warnings: MutableSet<String>): BigDecimal {
        val result = value.max(BigDecimal("0.5")).min(BigDecimal("2"))
        if (result.compareTo(value) != 0) warnings += "$label ограничен диапазоном 0.5–2: $value → $result."
        return result
    }

    private fun weightedDaily(observations: List<Observation>): BigDecimal {
        val quantity = observations.fold(ZERO) { sum, month -> sum.add(month.quantity.divide(month.seasonalIndex, MC)) }
        val days = observations.fold(ZERO) { sum, month -> sum.add(month.days) }
        return quantity.divide(days, MC)
    }

    private fun mean(values: List<BigDecimal>): BigDecimal =
        values.fold(ZERO, BigDecimal::add).divide(values.size.toBigDecimal(), MC)

    private fun quantile(sorted: List<BigDecimal>, fraction: BigDecimal): BigDecimal {
        val position = (sorted.size - 1).toBigDecimal().multiply(fraction)
        val lower = position.toInt()
        val remainder = position.subtract(lower.toBigDecimal())
        return sorted[lower].add(sorted[minOf(lower + 1, sorted.lastIndex)].subtract(sorted[lower]).multiply(remainder))
    }

    private fun isExpense(sale: PlanningSale): Boolean =
        sale.document?.trim()?.startsWith("Расходная накладная", ignoreCase = true) == true

    private fun output(value: BigDecimal): BigDecimal = value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()

    private data class DocumentKey(val date: LocalDate, val number: String)

    private data class Observation(val month: YearMonth, var quantity: BigDecimal, val seasonalIndex: BigDecimal) {
        val days: BigDecimal get() = month.lengthOfMonth().toBigDecimal()
        fun daily(): BigDecimal = quantity.divide(days, MC).divide(seasonalIndex, MC)
    }

    private companion object {
        val MC: MathContext = MathContext.DECIMAL128
        val ZERO: BigDecimal = BigDecimal.ZERO
        val ONE: BigDecimal = BigDecimal.ONE
        val THREE = BigDecimal("3")
        val HUNDRED = BigDecimal("100")
        const val MIN_HISTORY_MONTHS = 3
        const val MIN_ORDERS = 5
    }
}
