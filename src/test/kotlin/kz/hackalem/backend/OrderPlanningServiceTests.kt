package kz.hackalem.backend

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import kotlin.test.*

class OrderPlanningServiceTests {
    private val service = OrderPlanningService(mock(ExcelFileStore::class.java), IekOrderSource(), SystemeOrderSource(), DemandForecastEngine())
    private val parameters = OrderPlanningParameters(10, 20, 6, BigDecimal.ZERO)

    @Test
    fun `stock and incoming independently reduce the actual order`() {
        val product = product()
        val base = calculate(product).items.single()
        number("300", base.forecastQty)
        number("150", base.orderQty)
        number("140", calculate(product.copy(stock = BigDecimal("110"))).items.single().orderQty)
        number("140", calculate(product.copy(incoming = listOf(incoming("60")))).items.single().orderQty)
        assertTrue(base.explanation.contains("150"))
    }

    @Test
    fun `higher regular sales and explicit growth forecast increase order`() {
        val base = product()
        val increasedSales = base.copy(monthlySales = base.monthlySales.map { it.copy(value = it.value!! * BigDecimal("2")) })
        number("450", calculate(increasedSales).items.single().orderQty)
        number("450", service.calculate(listOf(source(base)), parameters.copy(forecastGrowthPercent = BigDecimal("100"))).items.single().orderQty)
    }

    @Test
    fun `anomalous purchases reduce the order and remain visible even when no order is needed`() {
        val original = product()
        val regularSales = original.monthlySales.mapIndexed { index, month ->
            PlanningSale(month.month.atDay(5), "regular-$index", "Расходная накладная", BigDecimal.TEN)
        }
        val projectSale = PlanningSale(LocalDate.of(2026, 6, 15), "project", "Расходная накладная", BigDecimal("1000"))
        val dirty = original.copy(
            monthlySales = original.monthlySales.map {
                if (it.month.monthValue == 6) it.copy(value = it.value!! + projectSale.quantity!!) else it
            },
            sales = regularSales + projectSale,
        )
        val result = calculate(dirty)
        number("150", result.items.single().orderQty)
        val excluded = result.excludedSales.single()
        assertEquals("systeme", excluded.supplierId)
        assertEquals(original.code, excluded.productCode)
        number("1000", excluded.excludedQuantity)
        assertEquals("project", excluded.sales.single().documentNumber)
        number("10", excluded.sales.single().averageOtherQuantity)
        number("30", excluded.sales.single().thresholdQuantity)
        assertEquals(6, excluded.sales.single().comparisonOrderCount)

        val sufficientStock = calculate(dirty.copy(stock = BigDecimal("500")))
        assertTrue(sufficientStock.items.isEmpty())
        assertEquals(excluded, sufficientStock.excludedSales.single())
        assertTrue(sufficientStock.review.single().reasons.any { "Исключены аномальные покупки" in it })
        number("1300", dirty.monthlySales.last().value!!)
        assertEquals(projectSale, dirty.sales.last(), "Planning must not remove original sales from memory")
    }

    @Test
    fun `anomaly baselines do not mix suppliers or products`() {
        val base = product()
        fun withPurchases(quantity: Int): PlanningProduct = base.copy(sales = (1..5).map {
            PlanningSale(LocalDate.of(2026, 6, it), "invoice-$it", "Расходная накладная",
                (if (it == 5) 100 else quantity).toBigDecimal())
        })
        val small = withPurchases(10)
        val large = withPurchases(100)
        val sources = listOf(source(small).copy(products = listOf(small, large.copy(code = "0002_"))),
            source(large).copy(supplierId = "iek", supplierName = "IEK"))
        val result = service.calculate(sources, parameters)
        assertEquals(1, result.excludedSales.size)
        assertEquals("systeme", result.excludedSales.single().supplierId)
        assertEquals("0001_", result.excludedSales.single().productCode)
    }

    @Test
    fun `future deliveries outside horizon are not deducted`() {
        val result = calculate(product().copy(incoming = listOf(incoming("50").copy(expectedBy = LocalDate.of(2026, 9, 1))))).items.single()
        number("50", result.inTransit)
        number("0", result.inTransitUsed)
        number("200", result.orderQty)
    }

    @Test
    fun `unknown transit is distinct from zero and makes recommendation provisional`() {
        val result = calculate(product().copy(incoming = listOf(PlanningIncoming(null, null, null)))).items.single()
        assertTrue(result.estimated)
        assertTrue(result.warnings.any { it.contains("Путь неполон") })
        number("200", result.orderQty)
    }

    @Test
    fun `minimum shipment is not confused with order multiple`() {
        val base = product().copy(stock = BigDecimal("239"), incoming = listOf(incoming("50")))
        number("20", calculate(base.copy(orderMultiple = BigDecimal.TEN)).items.single().orderQty)
        number("11", calculate(base.copy(orderMultiple = null, minimumShipment = BigDecimal("6"))).items.single().orderQty)
        number("20", calculate(base.copy(orderMultiple = null, minimumShipment = BigDecimal("20"))).items.single().orderQty)
    }

    @Test
    fun `sufficient stock does not create zero quantity order`() {
        val result = calculate(product().copy(stock = BigDecimal("500")))
        assertTrue(result.items.isEmpty())
        assertTrue(result.skipped.isEmpty())
        assertEquals(1, result.suppliers.single().noOrderNeeded)
    }

    @Test
    fun `uncertain transit suppressing an order still remains visible for review`() {
        val result = calculate(product().copy(stock = BigDecimal.ZERO,
            incoming = listOf(PlanningIncoming(BigDecimal("300"), null, "Без срока"))))
        assertTrue(result.items.isEmpty())
        assertEquals("0001_", result.review.single().productCode)
        assertTrue(result.review.single().reasons.any { it.contains("нет полной даты") })
    }

    @Test
    fun `missing stock ambiguous units and future stock are reported without invented order`() {
        for (product in listOf(
            product().copy(stock = null),
            product().copy(problems = listOf("Конфликт единиц м и бухта")),
            product().copy(stockMonth = YearMonth.of(2027, 1)),
            product().copy(incoming = listOf(incoming("-2"))),
        )) {
            val result = calculate(product)
            assertTrue(result.items.isEmpty())
            assertTrue(result.skipped.single().reasons.isNotEmpty())
            assertEquals(0, result.suppliers.single().noOrderNeeded)
        }
    }

    @Test
    fun `IEK monthly opening balance remains labelled an estimate`() {
        val result = calculate(product().copy(stockBasis = "MONTH_OPENING", stockMonth = YearMonth.of(2026, 7))).items.single()
        assertTrue(result.estimated)
        assertEquals("MONTH_OPENING", result.stockBasis)
        assertTrue(result.explanation.contains("остаток на начало 2026-07"))
    }

    @Test
    fun `category selects products without inventing business meaning for numeric codes`() {
        val source = source(product()).copy(products = listOf(product().copy(category = "1"), product().copy(code = "0002_", category = "7")))
        val result = service.calculate(listOf(source), parameters, "7")
        assertEquals("0002_", result.items.single().erpCode)
        assertEquals(1, result.suppliers.single().productsConsidered)
    }

    @Test
    fun `no uploaded snapshot and invalid parameters return client errors`() {
        assertEquals(409, assertFailsWith<ResponseStatusException> { service.recommend(parameters) }.statusCode.value())
        for (invalid in listOf(parameters.copy(leadDays = -1), parameters.copy(reviewDays = 0), parameters.copy(historyMonths = 0),
            parameters.copy(forecastGrowthPercent = BigDecimal("-101")), parameters.copy(anomalyMultiplier = BigDecimal.ONE),
            parameters.copy(anomalyMultiplier = BigDecimal("101")))) {
            assertEquals(400, assertFailsWith<ResponseStatusException> { service.recommend(invalid) }.statusCode.value())
        }
    }

    private fun calculate(product: PlanningProduct) = service.calculate(listOf(source(product)), parameters)
    private fun source(product: PlanningProduct) = PlanningSource("systeme", "Systeme Electric", LocalDate.of(2026, 7, 15),
        listOf(product), Month.entries.associateWith { BigDecimal.ONE })
    private fun incoming(quantity: String) = PlanningIncoming(BigDecimal(quantity), LocalDate.of(2026, 7, 20), "Поставка")
    private fun product(): PlanningProduct {
        val months = (1..6).map { YearMonth.of(2026, it) }
        return PlanningProduct("0001_", "ART", "Товар", "шт", monthlySales = months.map { MonthlyValue(it, (it.lengthOfMonth() * 10).toBigDecimal()) },
            stockHistory = months.map { MonthlyValue(it, BigDecimal("100")) }, stock = BigDecimal("100"),
            stockBasis = "AVAILABLE_STOCK", incoming = listOf(incoming("50")), orderMultiple = BigDecimal.TEN)
    }
    private fun number(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$expected != $actual")
}
