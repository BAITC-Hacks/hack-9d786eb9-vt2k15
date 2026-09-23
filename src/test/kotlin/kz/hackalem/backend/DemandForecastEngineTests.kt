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
        assertTrue(result.warnings.any { "ID клиента отсутствует" in it })
    }

    @Test
    fun `inconsistent large documents are warned about without subtracting from monthly sales`() {
        val months = dailyHistory(YearMonth.of(2026, 3), List(6) { 10 })
        val regular = months.mapIndexed { index, value -> sale(value.month.atDay(5), "regular-$index", 10) }
        val result = forecast(product(months, regular + sale(LocalDate.of(2026, 8, 15), "project", 10000)))

        assertDecimal("0", result.excludedOutlierQuantity)
        assertDecimal("300", result.forecastQuantity)
        assertTrue(result.warnings.any { "Несогласованность" in it })
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
