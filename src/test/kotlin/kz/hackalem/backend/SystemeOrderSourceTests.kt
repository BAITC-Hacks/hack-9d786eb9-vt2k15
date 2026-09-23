package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemeOrderSourceTests {
    private val adapter = SystemeOrderSource()
    private val period = YearMonth.of(2027, 1)

    @Test
    fun `union keeps codes from every product report and uses current available stock only`() {
        val data = data(
            moq = listOf(SystemeMoqRow(3, 1, "MOQ only", " 0001_ ", "ART", bd("5"))),
            stocks = listOf(stockRow("STOCK", "100")),
            monthly = listOf(monthlyRow("MONTHLY", "30")),
            snapshots = listOf(snapshot("SNAPSHOT", available = "8")),
            sales = listOf(sale("SALES", "2027-02-02T12:00:00", "2")),
        )

        val source = adapter.build(data)
        assertEquals("systeme", source.supplierId)
        assertEquals(LocalDate.of(2027, 2, 2), source.dataThrough)
        assertEquals(setOf("0001_", "STOCK", "MONTHLY", "SNAPSHOT", "SALES"), source.products.map { it.code }.toSet())
        val current = source.products.single { it.code == "SNAPSHOT" }
        assertDecimal("8", current.stock)
        assertEquals("AVAILABLE_STOCK", current.stockBasis)
        assertNull(current.stockMonth)
        assertEquals("3", current.category)
        assertDecimal("0.25", current.sourceGrowthChange)
        assertTrue(current.warnings.any { it.contains("рабочего снимка") })
        val historical = source.products.single { it.code == "STOCK" }
        assertNull(historical.stock)
        assertDecimal("100", historical.stockHistory.single().value)
        assertNull(historical.incoming.single().quantity)
        assertNull(historical.orderMultiple)
        assertTrue(historical.warnings.any { it.contains("Кратность отсутствует") })
        assertTrue(historical.problems.isEmpty())
    }

    @Test
    fun `null monthly sales do not fallback and unknown transit stays unknown while stock fallback is explicit`() {
        val row = snapshot("ITEM", available = null).copy(
            stock = bd("100"), reservedStock = bd("30"),
            inTransit = listOf(SystemeInTransitQuantity("СЭ в пути 24.09", "24.09", null), SystemeInTransitQuantity("СЭ в пути 25.09", "25.09", BigDecimal.ZERO)),
        )
        val source = adapter.build(data(monthly = listOf(monthlyRow("ITEM", null)), snapshots = listOf(row)))
        val product = source.products.single()
        assertNull(product.monthlySales.single().value)
        assertDecimal("70", product.stock)
        assertTrue(product.warnings.any { it.contains("Остаток − Зарезервировано") })
        assertNull(product.incoming.first().quantity)
        assertDecimal("0", product.incoming.last().quantity)
        assertTrue(product.incoming.all { it.expectedBy == null })
        assertEquals(listOf("24.09", "25.09"), product.incoming.map { it.reference })
        assertTrue(product.warnings.any { it.contains("полной датой") })
        assertTrue(product.warnings.any { it.contains("партии в пути неизвестно") })

        val zero = adapter.build(data(snapshots = listOf(row.copy(availableStock = BigDecimal.ZERO)))).products.single()
        assertDecimal("0", zero.stock)
        val unknown = adapter.build(data(snapshots = listOf(row.copy(reservedStock = null)), stocks = listOf(stockRow("ITEM", "90")))).products.single()
        assertNull(unknown.stock)
    }

    @Test
    fun `master duplicates collapse without summing while conflicts and units become problems`() {
        val record = snapshot("ITEM", available = "10")
        val moq = SystemeMoqRow(3, 1, "Product", "ITEM", "ARTICLE", bd("5"))
        val source = adapter.build(data(
            snapshots = listOf(record, record.copy(sourceRow = 7, availableStock = bd("10.00"))),
            moq = listOf(moq, moq.copy(sourceRow = 10, orderMultiple = bd("5.0"))),
            stocks = listOf(stockRow("ITEM", "10", "шт")),
            sales = listOf(sale("ITEM", "2026-01-02T12:00:00", "4"), sale("ITEM", "2027-01-02T12:00:00", "-1")),
        ))
        val product = source.products.single()
        assertDecimal("10", product.stock)
        assertEquals(1, product.incoming.size)
        assertEquals(2, product.sales.size)
        assertDecimal("-1", product.sales.last().quantity)
        assertTrue(product.problems.isEmpty())
        assertTrue(product.warnings.any { it.contains("объединены без суммирования") })

        val conflict = adapter.build(data(
            snapshots = listOf(record, record.copy(sourceRow = 8, availableStock = bd("11"))),
            stocks = listOf(stockRow("ITEM", "10", "метр")),
            sales = listOf(sale("ITEM", "2027-01-02T12:00:00", "2")),
        )).products.single()
        assertTrue(conflict.problems.any { it.contains("противоречивых строк") })
        assertTrue(conflict.problems.any { it.contains("Несовместимые единицы") })
    }

    @Test
    fun `seasonality prefers standalone combined factor and otherwise averages positive annual coefficients`() {
        val report = SystemeSeasonalityData("seasonality.xlsx", listOf(
            seasonal(Month.JANUARY, "2", "9", "8"),
            seasonal(Month.FEBRUARY, null, "2", "4", "0", "-1"),
            seasonal(Month.MARCH, "1", "1"), seasonal(Month.MARCH, "2", "2"),
            seasonal(null, "1", "1"),
        ), emptyList(), emptyList(), emptyList(), emptyList())
        val source = adapter.build(data(seasonality = report))
        assertDecimal("2", source.seasonality[Month.JANUARY])
        assertDecimal("3", source.seasonality[Month.FEBRUARY])
        assertNull(source.seasonality[Month.MARCH])
        assertTrue(source.warnings.any { it.contains("противоречивые итоговые") })
        assertTrue(source.warnings.any { it.contains("без определённого месяца") })
        assertTrue(source.warnings.any { it.contains("среднее положительных") })
    }

    private fun data(
        moq: List<SystemeMoqRow> = emptyList(),
        stocks: List<SystemeMonthlyStockRow> = emptyList(),
        monthly: List<SystemeMonthlySalesRow> = emptyList(),
        snapshots: List<SystemeIncomingShipmentRow> = emptyList(),
        sales: List<SystemeSalesDynamicsRow> = emptyList(),
        seasonality: SystemeSeasonalityData = SystemeSeasonalityData("seasonality.xlsx", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    ) = SystemeData(
        SystemeMoqData("moq.xlsx", moq), SystemeSalesDynamicsData("dynamics.xlsx", sales, emptyList()),
        SystemeMonthlyStocksData("stocks.xlsx", stocks), SystemeMonthlySalesData("sales.xlsx", monthly, emptyList(), emptyReport()),
        SystemeIncomingShipmentsData("incoming.xlsx", snapshots, emptyReport()), seasonality,
    )

    private fun snapshot(code: String, available: String?) = SystemeIncomingShipmentRow(
        sourceRow = 3, reportNumber = 1, supplierArticle = "ARTICLE", productCode = code, productName = "Product",
        category = SystemeCategory(2027, "3"), ssReal = null, monthlySales = listOf(MonthlyValue(period, bd("999"))),
        annualSales = emptyList(), reportedRollingSales = calculated(null), reportedAverageMonthlySales = calculated(null),
        growthChange = calculated("0.25"), seasonalityChange = calculated("0.1"), displayQuantity = bd("900"), tzStock = bd("800"),
        ryskulovaDistributionStock = bd("700"), retailWarehouseStock = bd("600"), stock = bd("500"), reservedStock = bd("400"),
        availableStock = available?.let(::bd), stockCoverageMonths = calculated("100"), proposedOrderQuantity = bd("200"),
        inTransit = listOf(SystemeInTransitQuantity("СЭ в пути 24.09", "24.09", bd("50"))), weight = null,
    )

    private fun stockRow(code: String, quantity: String, unit: String = "шт") =
        SystemeMonthlyStockRow(4, 1, "Product", code, unit, listOf(MonthlyValue(period, bd(quantity))))

    private fun monthlyRow(code: String, quantity: String?) =
        SystemeMonthlySalesRow(3, "Product", code, "ARTICLE", bd("999"), listOf(MonthlyValue(period, quantity?.let(::bd))), quantity?.let(::bd))

    private fun sale(code: String, timestamp: String, quantity: String) = SystemeSalesDynamicsRow(
        2, LocalDateTime.parse(timestamp), "REUSED_DOCUMENT_NUMBER", "Расходная накладная", code, "Product", "шт", "Алматы", bd(quantity),
    )

    private fun seasonal(month: Month?, combined: String?, vararg coefficients: String) = SystemeSeasonalMonthRow(
        11, month, SystemeCalculatedText("B11", month?.name, null, null),
        coefficients.mapIndexed { index, coefficient -> SystemeSeasonalYearMetrics(2024 + index, calculated(null), calculated(coefficient), calculated(null)) },
        combined?.let(::calculated),
    )

    private fun emptyReport() = SystemeSeasonalityReport("Лист1", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun calculated(value: String?) = SystemeCalculatedDecimal("A1", value?.let(::bd), null, null)
    private fun bd(value: String) = BigDecimal(value)
    private fun assertDecimal(expected: String, actual: BigDecimal?) = assertEquals(0, assertNotNull(actual).compareTo(bd(expected)))
}
