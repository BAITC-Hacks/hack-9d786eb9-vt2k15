package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IekOrderSourceTests {
    private val adapter = IekOrderSource()
    private val august = YearMonth.of(2026, 8)
    private val september = YearMonth.of(2026, 9)

    @Test
    fun `product union preserves codes and uses the common latest stock month including null`() {
        val data = data(
            moq = listOf(moq("MOQ_ONLY"), moq("0001_", minimum = "6")),
            stocks = listOf(
                stock("0001_", listOf(month(august, "25"), month(september, null))),
                stock("STOCK_ONLY", listOf(month(august, "0"), month(september, "0"))),
                stock("OLD_ROW", listOf(month(august, "9"))),
            ),
            monthlySales = listOf(monthlySale("MONTHLY_ONLY", listOf(month(august, "12")))),
            incoming = listOf(incoming("INCOMING_ONLY", "10")),
            dynamics = listOf(sale("DYNAMICS_ONLY", "2", LocalDateTime.of(2026, 9, 22, 10, 0))),
        )

        val source = adapter.build(data)
        val products = source.products.associateBy { it.code }

        assertEquals(setOf("MOQ_ONLY", "0001_", "STOCK_ONLY", "OLD_ROW", "MONTHLY_ONLY", "INCOMING_ONLY", "DYNAMICS_ONLY"), products.keys)
        assertEquals(LocalDate.of(2026, 9, 22), source.dataThrough)
        assertNull(products.getValue("0001_").stock)
        assertEquals(september, products.getValue("0001_").stockMonth)
        assertEquals("MONTH_OPENING", products.getValue("0001_").stockBasis)
        assertEquals(BigDecimal("25"), products.getValue("0001_").stockHistory.first().value)
        assertNull(products.getValue("OLD_ROW").stock)
        assertEquals(september, products.getValue("OLD_ROW").stockMonth)
        assertEquals(BigDecimal.ZERO, products.getValue("STOCK_ONLY").stock)
        assertEquals(BigDecimal("6"), products.getValue("0001_").minimumShipment)
        assertNull(products.getValue("0001_").orderMultiple)
        assertTrue(source.warnings.any { it.contains("на начало 2026-09") && it.contains("оценкой") })
    }

    @Test
    fun `equal duplicate master and transit values are not added and names do not cause conflicts`() {
        val firstStock = stock("0001_", listOf(month(september, "10")))
        val data = data(
            moq = listOf(moq("0001_", minimum = "6"), moq("0001_", minimum = "6.0").copy(sourceRow = 9, ordinal = 8, productName = "Другое название")),
            stocks = listOf(firstStock, firstStock.copy(sourceRow = 20, productName = "Обновлённое название", openingStocks = listOf(month(september, "10.0")))),
            incoming = listOf(incoming("0001_", "5"), incoming("0001_", "5.0").copy(sourceRow = 30, productName = "Иное название")),
        )

        val product = adapter.build(data).products.single()

        assertTrue(product.problems.isEmpty(), product.problems.joinToString())
        assertEquals(BigDecimal("10"), product.stock)
        assertEquals(BigDecimal("6"), product.minimumShipment)
        assertEquals(2, product.incoming.size)
        assertEquals(BigDecimal("5"), product.incoming.first().quantity)
        assertNull(product.incoming.last().quantity)
        assertEquals(LocalDate.of(2026, 10, 10), product.incoming.first().expectedBy)
        assertEquals("РФ УТ-1 от 31 августа 2026 г. (поступление до 10.10.2026)", product.incoming.first().reference)
    }

    @Test
    fun `conflicting quantities articles and units block a product instead of silently summing`() {
        val data = data(
            moq = listOf(moq("0001_", minimum = "6"), moq("0001_", minimum = "12")),
            stocks = listOf(
                stock("0001_", listOf(month(september, "10"))),
                stock("0001_", listOf(month(september, "11"))),
            ),
            monthlySales = listOf(
                monthlySale("0001_", listOf(month(august, "2"))),
                monthlySale("0001_", listOf(month(august, "3"))),
            ),
            incoming = listOf(incoming("0001_", "5"), incoming("0001_", "6").copy(supplierArticle = "OTHER")),
            dynamics = listOf(sale("0001_", "1", unit = "м")),
        )

        val product = adapter.build(data).products.single()

        assertNull(product.stock)
        assertNull(product.minimumShipment)
        assertNull(product.incoming.first().quantity)
        assertNull(product.monthlySales.single().value)
        assertNull(product.article)
        assertNull(product.unit)
        assertTrue(product.problems.any { it.contains("разные артикулы") })
        assertTrue(product.problems.any { it.contains("единицы измерения") })
        assertTrue(product.problems.any { it.contains("товара в пути") })
        assertTrue(product.problems.any { it.contains("остатков") })
        assertTrue(product.problems.any { it.contains("месячных продаж") })
    }

    @Test
    fun `reels versus meters without a conversion coefficient are reported as a problem`() {
        val data = data(
            stocks = listOf(stock("CABLE", listOf(month(september, "305")), unit = "м")),
            incoming = listOf(incoming("CABLE", "1").copy(productName = "Кабель ITK ЗАКУПАЮТСЯ БУХТАМИ, САДЯТСЯ МЕТРАЖОМ")),
        )

        val product = adapter.build(data).products.single()

        assertTrue(product.problems.any { it.contains("коэффициента пересчёта") })
        assertEquals(BigDecimal("305"), product.stock)
        assertEquals(BigDecimal.ONE, product.incoming.first().quantity)
    }

    @Test
    fun `transactions remain signed nullable and repeated while missing codes become summary warnings`() {
        val repeated = sale("0001_", "2", LocalDateTime.of(2026, 9, 20, 9, 30))
        val data = data(
            moq = listOf(moq(null)),
            stocks = listOf(stock(null, listOf(month(september, "4")))),
            monthlySales = listOf(monthlySale(null, listOf(month(august, "4")))),
            incoming = listOf(incoming(null, "4")),
            dynamics = listOf(
                repeated, repeated.copy(sourceRow = 3), sale("0001_", "-3"), sale("0001_", null),
                sale(null, "8", LocalDateTime.of(2026, 9, 22, 15, 0)),
            ),
        )

        val source = adapter.build(data)
        val product = source.products.single()

        assertEquals("0001_", product.code)
        assertEquals(4, product.sales.size)
        assertEquals(listOf(BigDecimal("2"), BigDecimal("2"), BigDecimal("-3"), null), product.sales.map { it.quantity })
        assertEquals(repeated.documentNumber, product.sales.first().documentNumber)
        assertEquals(repeated.document, product.sales.first().document)
        assertEquals(LocalDate.of(2026, 9, 22), source.dataThrough)
        assertEquals(5, source.warnings.count { it.contains("без кода 1С") })
    }

    @Test
    fun `normalized seasonality has priority and missing or invalid coefficients use yearly fallback`() {
        val seasonality = emptySeasonality().copy(
            normalizedSeasonality = listOf(normalized(1, "1.2"), normalized(2, null), normalized(3, "-1")),
            yearlySeasonality = listOf(yearly(1, "0.8"), yearly(2, "0.9"), yearly(3, "1.1"), yearly(4, "0")),
        )

        val source = adapter.build(data(seasonality = seasonality))

        assertEquals(BigDecimal("1.2"), source.seasonality[Month.JANUARY])
        assertEquals(BigDecimal("0.9"), source.seasonality[Month.FEBRUARY])
        assertEquals(BigDecimal("1.1"), source.seasonality[Month.MARCH])
        assertFalse(source.seasonality.containsKey(Month.APRIL))
        assertTrue(source.warnings.any { it.contains("неположительный коэффициент") })
    }

    private fun data(
        moq: List<IekMoqRow> = emptyList(),
        stocks: List<IekMonthlyStockRow> = emptyList(),
        monthlySales: List<IekMonthlySalesRow> = emptyList(),
        incoming: List<IekIncomingShipmentRow> = emptyList(),
        dynamics: List<IekSalesDynamicsRow> = emptyList(),
        seasonality: IekSeasonalityData = emptySeasonality(),
    ) = IekData(
        IekMoqData("moq.xlsx", moq),
        IekSalesDynamicsData("dynamics.xlsx", dynamics, emptyList()),
        IekMonthlyStocksData("stocks.xlsx", stocks, emptyList()),
        IekMonthlySalesData("sales.xlsx", monthlySales, emptyList()),
        IekIncomingShipmentsData("incoming.xlsx", listOf(
            IekShipmentColumn("D", "РФ УТ-1 от 31 августа 2026 г. (поступление до 10.10.2026)", "РФ", "УТ-1", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 10, 10)),
            IekShipmentColumn("E", "Партия с неизвестной датой", null, "УТ-2", null, null),
        ), incoming, emptyList()),
        seasonality,
    )

    private fun moq(code: String?, minimum: String? = "1") =
        IekMoqRow(2, 1, code, "ARTICLE", "Товар", minimum?.toBigDecimal())

    private fun stock(code: String?, values: List<MonthlyValue>, unit: String = "шт") =
        IekMonthlyStockRow(4, "Товар", unit, code, values, null)

    private fun monthlySale(code: String?, values: List<MonthlyValue>) =
        IekMonthlySalesRow(3, "Товар", code, values, null)

    private fun incoming(code: String?, quantity: String?) =
        IekIncomingShipmentRow(2, code, "ARTICLE", "Товар", listOf(
            IekShipmentQuantity("D", quantity?.toBigDecimal()), IekShipmentQuantity("E", null),
        ))

    private fun sale(
        code: String?, quantity: String?, date: LocalDateTime? = null, unit: String = "шт",
    ) = IekSalesDynamicsRow(2, date, "0000123", "Расходная накладная 0000123", code, "Товар", unit, "Алматы", quantity?.toBigDecimal())

    private fun month(month: YearMonth, quantity: String?) = MonthlyValue(month, quantity?.toBigDecimal())

    private fun emptySeasonality() = IekSeasonalityData("seasonality.xlsx", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun normalized(month: Int, coefficient: String?) =
        IekNormalizedSeasonalityRow(month + 27, null, month, emptyList(), null, coefficient?.toBigDecimal(), null, null)

    private fun yearly(month: Int, coefficient: String?) =
        IekYearlySeasonalityRow(month + 10, null, month, emptyList(), coefficient?.toBigDecimal())
}
