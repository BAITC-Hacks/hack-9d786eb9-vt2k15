package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaleAnomalyDetectorTests {
    private val detector = SaleAnomalyDetector()
    private val january = YearMonth.of(2026, 1)

    @Test
    fun `the multiplier threshold is strict and is compared without rounding`() {
        val ordinary = ordinaryOrders(4)
        assertTrue(detect(ordinary + sale("large", "30")).anomalies.isEmpty())

        val anomaly = detect(ordinary + sale("large", "30.00000000000000000000000000000000001")).anomalies.single()

        assertEquals("large", anomaly.documentNumber)
        assertEquals(4, anomaly.comparisonOrderCount)
        assertDecimal("10", anomaly.averageOtherQuantity)
        assertDecimal("30", anomaly.thresholdQuantity)
        assertDecimal("30.00000000000000000000000000000000001", anomaly.quantity)
    }

    @Test
    fun `at least five complete positive documents are required`() {
        val insufficient = detect(ordinaryOrders(3) + sale("large", "1000"))
        assertTrue(insufficient.anomalies.isEmpty())
        assertTrue(insufficient.warnings.any { it.contains("минимум 5") && it.contains("найдено 4") })

        val enough = detect(ordinaryOrders(4) + sale("large", "1000"))
        assertEquals(listOf("large"), enough.anomalies.map { it.documentNumber })
        assertEquals(4, enough.anomalies.single().comparisonOrderCount)
    }

    @Test
    fun `only dates within the inclusive selected month range affect the comparison`() {
        val sales = listOf(
            sale("start", "10", LocalDate.of(2025, 12, 1)),
            sale("december-end", "10", LocalDate.of(2025, 12, 31)),
            sale("january-start", "10", LocalDate.of(2026, 1, 1)),
            sale("ordinary", "10", LocalDate.of(2026, 1, 30)),
            sale("large", "40", LocalDate.of(2026, 1, 31)),
            sale("before", "1000000", LocalDate.of(2025, 11, 30)),
            sale("after", "1000000", LocalDate.of(2026, 2, 1)),
            sale("incomplete-outside", null, LocalDate.of(2026, 2, 2)),
        )

        val result = detector.detect(sales, YearMonth.of(2025, 12), january, BigDecimal("3"))

        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertEquals(4, result.anomalies.single().comparisonOrderCount)
        assertDecimal("10", result.anomalies.single().averageOtherQuantity)
        assertOnlyDocumentWarning(result)
    }

    @Test
    fun `the full date keeps identical document numbers in different years and days separate`() {
        val sales = listOf(
            sale("00001", "10", LocalDate.of(2025, 1, 10)),
            sale("00001", "10", LocalDate.of(2025, 1, 11)),
            sale("ordinary-1", "10", LocalDate.of(2025, 2, 1)),
            sale("ordinary-2", "10", LocalDate.of(2025, 3, 1)),
            sale(" 00001 ", "40", LocalDate.of(2026, 1, 10)),
        )

        val anomaly = detector.detect(sales, YearMonth.of(2025, 1), january, BigDecimal("3")).anomalies.single()

        assertEquals("00001", anomaly.documentNumber)
        assertEquals(LocalDate.of(2026, 1, 10), anomaly.documentDate)
        assertEquals(4, anomaly.comparisonOrderCount)
        assertDecimal("40", anomaly.quantity)
    }

    @Test
    fun `all lines with the same date and trimmed document number form one order`() {
        val sales = ordinaryOrders(4) + listOf(sale(" joined ", "20"), sale("joined", "15"))

        val anomaly = detect(sales).anomalies.single()

        assertEquals("joined", anomaly.documentNumber)
        assertDecimal("35", anomaly.quantity)
        assertEquals(4, anomaly.comparisonOrderCount)
    }

    @Test
    fun `signed returns inside a document reduce its total before anomaly detection`() {
        val result = detect(ordinaryOrders(4) + listOf(
            sale("net-order", "100"), sale("net-order", "-75"),
            sale("zero-order", "2000"), sale("zero-order", "-2000"),
            sale("negative-order", "-1000"),
        ))

        assertTrue(result.anomalies.isEmpty())
        assertOnlyDocumentWarning(result)
    }

    @Test
    fun `zero and negative document totals cannot satisfy the minimum positive sample`() {
        val result = detect(ordinaryOrders(3) + listOf(
            sale("large", "1000"), sale("zero", "10"), sale("zero", "-10"), sale("negative", "-2"),
        ))

        assertTrue(result.anomalies.isEmpty())
        assertTrue(result.warnings.any { it.contains("найдено 4") })
    }

    @Test
    fun `one unknown line excludes its whole document from anomalies and the baseline`() {
        val sales = ordinaryOrders(4) + listOf(
            sale("large", "40"), sale("incomplete", "1000000"), sale("incomplete", null),
        )

        val result = detect(sales)

        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertEquals(4, result.anomalies.single().comparisonOrderCount)
        assertDecimal("10", result.anomalies.single().averageOtherQuantity)
        assertTrue(result.warnings.any { it.contains("неизвестным количеством") && it.endsWith(": 1.") })

        val insufficient = detect(ordinaryOrders(4) + listOf(sale("incomplete", "1000"), sale("incomplete", null)))
        assertTrue(insufficient.anomalies.isEmpty())
        assertTrue(insufficient.warnings.any { it.contains("найдено 4") })
    }

    @Test
    fun `missing date or document number is reported and cannot influence the baseline`() {
        val result = detect(ordinaryOrders(4) + listOf(
            sale("large", "40"), sale("undated", "1000000", null),
            sale(null, "1000000"), sale("   ", "1000000"),
        ))

        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertEquals(4, result.anomalies.single().comparisonOrderCount)
        assertTrue(result.warnings.any { it.contains("без даты") && it.endsWith(": 1.") })
        assertTrue(result.warnings.any { it.contains("без номера") && it.endsWith(": 2.") })
    }

    @Test
    fun `other document types and absent types are ignored rather than classified as expense orders`() {
        val result = detect(ordinaryOrders(4) + listOf(
            sale("large", "40"),
            sale("incoming", "1000000").copy(document = "Приходная накладная incoming"),
            sale("customer-order", "1000000").copy(document = "Заказ покупателя customer-order"),
            sale("unknown", "1000000").copy(document = null),
            sale(null, null, null).copy(document = "Приходная накладная"),
        ))

        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertEquals(4, result.anomalies.single().comparisonOrderCount)
        assertOnlyDocumentWarning(result)
    }

    @Test
    fun `repeated equally large orders are the normal baseline and are not anomalies`() {
        val result = detect((1..6).map { sale("bulk-$it", "1000000") })

        assertTrue(result.anomalies.isEmpty())
        assertOnlyDocumentWarning(result)
    }

    @Test
    fun `the shared expense rule handles case and whitespace without matching other prefixes`() {
        val large = sale("large", "40").copy(document = "  РАСХОДНАЯ\u00a0  НАКЛАДНАЯ large")
        val falsePrefix = sale("not-expense", "1000000").copy(document = "Расходная накладнаяПодмена")

        val result = detect(ordinaryOrders(4) + listOf(large, falsePrefix))

        assertTrue(isExpenseSale(large))
        assertTrue(!isExpenseSale(falsePrefix))
        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertOnlyDocumentWarning(result)
    }

    @Test
    fun `the order under test is excluded from its own average to prevent self masking`() {
        val anomaly = detect(ordinaryOrders(4) + sale("large", "35")).anomalies.single()

        // Including the order would give average 15 and threshold 45, masking the order of 35.
        assertDecimal("10", anomaly.averageOtherQuantity)
        assertDecimal("30", anomaly.thresholdQuantity)
        assertDecimal("35", anomaly.quantity)
    }

    @Test
    fun `a detected anomaly is not iteratively removed when evaluating other orders`() {
        val result = detect(ordinaryOrders(4) + listOf(sale("medium", "50"), sale("large", "1000")))

        assertEquals(listOf("large"), result.anomalies.map { it.documentNumber })
        assertEquals(5, result.anomalies.single().comparisonOrderCount)
        assertDecimal("18", result.anomalies.single().averageOtherQuantity)
        assertDecimal("54", result.anomalies.single().thresholdQuantity)
    }

    @Test
    fun `results are deterministic and sorted by date then document number`() {
        val sales = ordinaryOrders(3) + listOf(
            sale("B", "50", LocalDate.of(2026, 1, 10)),
            sale("A", "60", LocalDate.of(2026, 1, 10)),
            sale("Z", "70", LocalDate.of(2026, 1, 3)),
            sale(null, "99"), sale("undated", "99", null),
        )

        val result = detect(sales, "1.1")

        assertEquals(listOf("Z", "A", "B"), result.anomalies.map { it.documentNumber })
        assertEquals(result, detect(sales.reversed(), "1.1"))
    }

    @Test
    fun `reported averages use DECIMAL128 without changing the exact decision`() {
        val sales = (1..5).map { sale("one-$it", "1") } + listOf(sale("two", "2"), sale("large", "5"))
        val anomaly = detect(sales).anomalies.single()

        assertEquals(6, anomaly.comparisonOrderCount)
        assertEquals(BigDecimal("7").divide(BigDecimal("6"), MathContext.DECIMAL128), anomaly.averageOtherQuantity)
        assertDecimal("3.5", anomaly.thresholdQuantity)
    }

    @Test
    fun `multiplier must be greater than one and history dates must be ordered`() {
        listOf("-2", "0", "0.99", "1").forEach { multiplier ->
            assertFailsWith<IllegalArgumentException> { detect(emptyList(), multiplier) }
        }
        assertFailsWith<IllegalArgumentException> {
            detector.detect(emptyList(), january.plusMonths(1), january, BigDecimal("3"))
        }
    }

    private fun ordinaryOrders(count: Int) = (1..count).map { sale("ordinary-$it", "10") }

    private fun detect(sales: List<PlanningSale>, multiplier: String = "3") =
        detector.detect(sales, january, january, multiplier.toBigDecimal())

    private fun sale(number: String?, quantity: String?, date: LocalDate? = LocalDate.of(2026, 1, 15)) =
        PlanningSale(date, number, "Расходная накладная ${number.orEmpty()}", quantity?.toBigDecimal())

    private fun assertDecimal(expected: String, actual: BigDecimal) =
        assertEquals(0, expected.toBigDecimal().compareTo(actual), "Expected $expected, actual $actual")

    private fun assertOnlyDocumentWarning(result: SaleAnomalyDetection) {
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().startsWith("ID клиента отсутствует:"))
    }
}
