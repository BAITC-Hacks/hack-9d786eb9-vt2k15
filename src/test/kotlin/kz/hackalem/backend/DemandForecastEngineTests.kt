package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemandForecastEngineTests {
    private val engine = DemandForecastEngine()
    private val through = LocalDate.of(2026, 8, 31)
    private val parameters = OrderPlanningParameters(20, 10, 6, BigDecimal.ZERO)

    @Test
    fun `seasonality changes demand in the forecast calendar months`() {
        val product = product(dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }))
        val neutral = forecast(product)
        val seasonal = requireNotNull(engine.forecast(product, source(product).copy(
            seasonality = Month.entries.associateWith { if (it == Month.SEPTEMBER) decimal(2) else decimal(1) },
        ), parameters))

        assertDecimal("300", neutral.forecastQuantity)
        assertDecimal("600", seasonal.forecastQuantity)
        assertTrue(seasonal.seasonalFactor > BigDecimal.ONE)
    }

    @Test
    fun `only sustained change across several months becomes a trend`() {
        val persistent = product(dailyHistory(YearMonth.of(2026, 3), listOf(10, 10, 10, 20, 20, 20)))
        val oneMonthSpike = product(dailyHistory(YearMonth.of(2026, 3), listOf(10, 10, 10, 10, 10, 100)))

        assertDecimal("2", forecast(persistent).trendFactor)
        assertDecimal("1", forecast(oneMonthSpike).trendFactor)
        assertTrue(forecast(persistent).forecastQuantity > forecast(product(dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }))).forecastQuantity)
    }

    @Test
    fun `possible stockout raises lost demand while unknown stock does not`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }).map {
            if (it.month == YearMonth.of(2026, 5)) it.copy(value = BigDecimal.ZERO) else it
        }
        val product = product(months)
        val restored = forecast(product.copy(stockHistory = months.map {
            MonthlyValue(it.month, if (it.month == YearMonth.of(2026, 5)) BigDecimal.ZERO else decimal(50))
        }))
        val unknown = forecast(product.copy(stockHistory = months.map { MonthlyValue(it.month, null) }))

        assertDecimal("310", restored.stockoutAdjustment)
        assertDecimal("300", restored.forecastQuantity)
        assertTrue(restored.forecastQuantity > unknown.forecastQuantity)
        assertDecimal("0", unknown.stockoutAdjustment)
        assertTrue(restored.warnings.any { "возможного stockout" in it })
    }

    @Test
    fun `huge one time order split into document lines does not inflate regular demand`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val ordinary = product(months, regular)
        val projectDate = LocalDate.of(2026, 8, 15)
        val project = product(months.map {
            if (it.month == YearMonth.of(2026, 8)) it.copy(value = requireNotNull(it.value).add(decimal(10000))) else it
        }, regular + listOf(sale(projectDate, "project", 4000), sale(projectDate, "project", 6000)))

        val result = forecast(project)
        assertDecimal("10000", result.excludedOutlierQuantity)
        assertEquals(forecast(ordinary).forecastQuantity, result.forecastQuantity)
        val excluded = result.excludedSales.single()
        assertEquals(projectDate, excluded.documentDate)
        assertEquals("project", excluded.documentNumber)
        assertDecimal("10000", excluded.quantity)
        assertEquals(6, excluded.comparisonOrderCount)
        assertDecimal("10", excluded.averageOtherQuantity)
        assertDecimal("30", excluded.thresholdQuantity)
        assertTrue(result.warnings.any { "Исключены аномальные покупки" in it })
    }

    @Test
    fun `anomaly exceeding monthly sales removes the dirty month and reports the actual removed quantity`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val result = forecast(product(months, regular + sale(LocalDate.of(2026, 8, 15), "project", 10000)))
        val clean = forecast(product(months.map {
            if (it.month == YearMonth.of(2026, 8)) it.copy(value = BigDecimal.ZERO) else it
        }))

        assertDecimal("310", result.excludedOutlierQuantity)
        assertDecimal("10000", result.excludedSales.single().quantity)
        assertEquals(clean.baseDailyDemand, result.baseDailyDemand)
        assertEquals(clean.forecastQuantity, result.forecastQuantity)
        assertTrue(result.forecastQuantity < decimal(300))
        assertTrue(result.warnings.any { "Несогласованность" in it })
    }

    @Test
    fun `anomalies neutralize unclean source growth but explicit forecast growth still applies`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val dirty = product(months.map {
            if (it.month == YearMonth.of(2026, 8)) it.copy(value = requireNotNull(it.value) + decimal(10000)) else it
        }, regular + sale(LocalDate.of(2026, 8, 15), "project", 10000)).copy(sourceGrowthChange = decimal(9))

        val result = assertNotNull(engine.forecast(dirty, source(dirty), parameters.copy(forecastGrowthPercent = decimal(20))))

        assertDecimal("1", result.sourceGrowthFactor)
        assertDecimal("1", result.trendFactor)
        assertDecimal("360", result.forecastQuantity)
        assertDecimal("10000", result.excludedOutlierQuantity)
        assertDecimal("9", requireNotNull(dirty.sourceGrowthChange))
        assertTrue(result.warnings.any { "коэффициент роста не применён" in it })
    }

    @Test
    fun `stockout restoration uses the reference months after anomaly exclusion`() {
        val stockoutMonth = YearMonth.of(2026, 5)
        val projectMonth = YearMonth.of(2026, 8)
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }).map {
            if (it.month == stockoutMonth) it.copy(value = BigDecimal.ZERO) else it
        }
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val stocks = months.map { MonthlyValue(it.month, if (it.month == stockoutMonth) BigDecimal.ZERO else decimal(50)) }
        val clean = product(months, regular).copy(stockHistory = stocks)
        val dirty = clean.copy(
            monthlySales = months.map {
                if (it.month == projectMonth) it.copy(value = requireNotNull(it.value) + decimal(10000)) else it
            },
            sales = regular + sale(projectMonth.atDay(15), "project", 10000),
        )

        val result = forecast(dirty)
        val baseline = forecast(clean)

        assertDecimal("10000", result.excludedOutlierQuantity)
        assertDecimal("310", result.stockoutAdjustment)
        assertEquals(baseline.stockoutAdjustment, result.stockoutAdjustment)
        assertEquals(baseline.baseDailyDemand, result.baseDailyDemand)
        assertEquals(baseline.forecastQuantity, result.forecastQuantity)
    }

    @Test
    fun `repeated large documents are removed before evaluating sustained trend`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val projects = months.takeLast(3).map { sale(it.month.atDay(15), "project-${it.month}", 1000) }
        val dirty = product(months.mapIndexed { index, value ->
            if (index >= 3) value.copy(value = requireNotNull(value.value) + decimal(1000)) else value
        }, regular + projects)

        val result = forecast(dirty)

        assertEquals(3, result.excludedSales.size)
        assertDecimal("3000", result.excludedOutlierQuantity)
        assertDecimal("1", result.trendFactor)
        assertDecimal("10", result.baseDailyDemand)
        assertEquals(forecast(product(months, regular)).forecastQuantity, result.forecastQuantity)
    }

    @Test
    fun `dynamics fallback excludes an anomalous document exactly once`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", requireNotNull(value.value).toInt()) }
        val clean = product(emptyList(), regular)
        val dirty = product(emptyList(), regular + sale(LocalDate.of(2026, 8, 15), "project", 10000))

        val result = forecast(dirty)

        assertDecimal("10000", result.excludedOutlierQuantity)
        assertEquals("project", result.excludedSales.single().documentNumber)
        assertEquals(forecast(clean).forecastQuantity, result.forecastQuantity)
        assertDecimal("300", result.forecastQuantity)
        assertDecimal("10000", requireNotNull(dirty.sales.last().quantity))
        assertTrue(result.warnings.any { "восстановлена из расходных накладных" in it })
    }

    @Test
    fun `history months changes both comparison purchases and anomaly selection period`() {
        val months = dailyHistory(YearMonth.of(2025, 9), List(12) { 10 }).mapIndexed { index, value ->
            when {
                index < 6 -> value.copy(value = decimal(1000))
                index == 11 -> value.copy(value = requireNotNull(value.value) + decimal(100))
                else -> value
            }
        }
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", if (index < 6) 1000 else 10) }
        val product = product(months, regular + sale(LocalDate.of(2026, 8, 15), "candidate", 100))

        val recent = assertNotNull(engine.forecast(product, source(product), parameters.copy(historyMonths = 6)))
        val full = assertNotNull(engine.forecast(product, source(product), parameters.copy(historyMonths = 12)))

        assertEquals(YearMonth.of(2026, 3), recent.historyFrom)
        assertEquals(YearMonth.of(2025, 9), full.historyFrom)
        assertEquals("candidate", recent.excludedSales.single().documentNumber)
        assertDecimal("10", recent.excludedSales.single().averageOtherQuantity)
        assertDecimal("100", recent.excludedOutlierQuantity)
        assertTrue(full.excludedSales.isEmpty())
        assertDecimal("0", full.excludedOutlierQuantity)
    }

    @Test
    fun `custom anomaly multiplier changes both exclusion and resulting forecast`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }).map {
            if (it.month == YearMonth.of(2026, 8)) it.copy(value = requireNotNull(it.value) + decimal(40)) else it
        }
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val product = product(months, regular + sale(LocalDate.of(2026, 8, 15), "candidate", 40))

        val strict = assertNotNull(engine.forecast(product, source(product), parameters.copy(anomalyMultiplier = decimal(3))))
        val tolerant = assertNotNull(engine.forecast(product, source(product), parameters.copy(anomalyMultiplier = decimal(6))))

        assertDecimal("3", parameters.anomalyMultiplier)
        assertDecimal("40", strict.excludedOutlierQuantity)
        assertDecimal("30", strict.excludedSales.single().thresholdQuantity)
        assertDecimal("300", strict.forecastQuantity)
        assertTrue(tolerant.excludedSales.isEmpty())
        assertDecimal("0", tolerant.excludedOutlierQuantity)
        assertTrue(tolerant.forecastQuantity > strict.forecastQuantity)
    }

    @Test
    fun `anomalies in unknown monthly sales are not reported as removed sales`() {
        val unknownMonth = YearMonth.of(2026, 5)
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }).map {
            if (it.month == unknownMonth) it.copy(value = null) else it
        }
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val product = product(months, regular + sale(unknownMonth.atDay(15), "unknown-month-project", 10000))

        val result = forecast(product)

        assertTrue(result.excludedSales.isEmpty())
        assertDecimal("0", result.excludedOutlierQuantity)
        assertDecimal("300", result.forecastQuantity)
        assertTrue(result.warnings.any { "месячные продажи неизвестны" in it })
    }

    @Test
    fun `negative months never give negative demand and source date excludes incomplete month`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val negative = product(months.mapIndexed { index, value -> if (index == 0) value.copy(value = decimal(-100)) else value } +
            MonthlyValue(YearMonth.of(2026, 9), decimal(999999)))
        val result = assertNotNull(engine.forecast(negative, source(negative).copy(dataThrough = LocalDate.of(2026, 9, 22)), parameters))
        val withoutPartial = negative.copy(monthlySales = negative.monthlySales.dropLast(1).map {
            if (it.value?.signum() == -1) it.copy(value = BigDecimal.ZERO) else it
        })
        val expected = assertNotNull(engine.forecast(withoutPartial, source(withoutPartial).copy(dataThrough = LocalDate.of(2026, 9, 22)), parameters))

        assertEquals(YearMonth.of(2026, 8), result.historyThrough)
        assertEquals(YearMonth.of(2026, 3), result.historyFrom)
        assertEquals(expected.forecastQuantity, result.forecastQuantity)
        assertTrue(result.baseDailyDemand.signum() >= 0)
        assertTrue(result.warnings.any { "Отрицательные продажи" in it })
        assertTrue(result.warnings.any { "Неполный месяц 2026-09" in it })
    }

    @Test
    fun `source growth and forecast percentage are separate factors with a bounded source rate`() {
        val product = product(dailyHistory(YearMonth.of(2026, 3), List(6) { 10 }))
        val growing = product.copy(sourceGrowthChange = BigDecimal("0.5"))
        val result = assertNotNull(engine.forecast(growing, source(growing), parameters.copy(forecastGrowthPercent = decimal(20))))

        assertDecimal("1.5", result.sourceGrowthFactor)
        assertDecimal("540", result.forecastQuantity)
        val clamped = forecast(product.copy(sourceGrowthChange = decimal(10)))
        assertDecimal("2", clamped.sourceGrowthFactor)
        assertTrue(clamped.warnings.any { "Исходный коэффициент роста ограничен" in it })
    }

    @Test
    fun `forecast counts leap February days instead of thirty day months`() {
        val product = product(dailyHistory(YearMonth.of(2023, 8), List(6) { 10 }))
        val result = assertNotNull(engine.forecast(product, source(product).copy(dataThrough = LocalDate.of(2024, 1, 31)), parameters.copy(leadDays = 29, reviewDays = 0)))

        assertDecimal("10", result.baseDailyDemand)
        assertDecimal("290", result.forecastQuantity)
    }

    @Test
    fun `unknown history cannot become zero demand but observed zeros can`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 0 })
        val unknown = product(months.map { it.copy(value = null) })
        assertNull(engine.forecast(unknown, source(unknown), parameters))
        val insufficient = product(months.take(2))
        assertNull(engine.forecast(insufficient, source(insufficient), parameters))
        assertDecimal("0", forecast(product(months)).forecastQuantity)
    }

    @Test
    fun `dynamics fallback uses common complete history and never adds to monthly reports`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val sales = months.mapIndexed { index, value -> sale(value.month.atDay(10), "document-$index", requireNotNull(value.value).toInt()) }
        val fallbackProduct = product(emptyList(), sales)
        val fallback = forecast(fallbackProduct)
        val reportedProduct = product(months, sales)

        assertEquals(forecast(reportedProduct).forecastQuantity, fallback.forecastQuantity)
        assertTrue(fallback.warnings.any { "восстановлена из расходных накладных" in it })
        val emptyProduct = product(emptyList())
        assertNull(engine.forecast(emptyProduct, source(emptyProduct).copy(products = listOf(emptyProduct, reportedProduct)), parameters))
    }

    private fun forecast(product: PlanningProduct): DemandForecast =
        assertNotNull(engine.forecast(product, source(product), parameters))

    private fun product(months: List<MonthlyValue>, sales: List<PlanningSale> = emptyList()) = PlanningProduct(
        code = "001_", article = "ARTICLE", name = "Test product", unit = "шт",
        monthlySales = months, sales = sales, stockBasis = "test",
    )

    private fun source(product: PlanningProduct) = PlanningSource(
        supplierId = "iek", supplierName = "IEK", dataThrough = through,
        products = listOf(product), seasonality = Month.entries.associateWith { BigDecimal.ONE },
    )

    private fun dailyHistory(start: YearMonth, perDay: List<Int>) = perDay.mapIndexed { index, daily ->
        val month = start.plusMonths(index.toLong())
        MonthlyValue(month, decimal(daily).multiply(month.lengthOfMonth().toBigDecimal()))
    }

    private fun sale(date: LocalDate, number: String, quantity: Int) =
        PlanningSale(date, number, "Расходная накладная $number", decimal(quantity))

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Expected $expected, got $actual")
    }

    private fun decimal(value: Int) = value.toBigDecimal()
}
