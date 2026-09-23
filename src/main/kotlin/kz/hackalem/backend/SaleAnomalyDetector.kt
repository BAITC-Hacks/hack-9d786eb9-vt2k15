package kz.hackalem.backend

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth

data class SaleAnomaly(
    val documentDate: LocalDate,
    val documentNumber: String,
    val quantity: BigDecimal,
    val comparisonOrderCount: Int,
    val averageOtherQuantity: BigDecimal,
    val thresholdQuantity: BigDecimal,
)

data class SaleAnomalyDetection(
    val anomalies: List<SaleAnomaly>,
    val warnings: List<String>,
)

private val expenseDocumentType = Regex("^расходная\\s+накладная(?:\\s|$)", RegexOption.IGNORE_CASE)

/** The same document-type rule is shared by monthly aggregation and anomaly detection. */
internal fun isExpenseSale(sale: PlanningSale): Boolean = sale.document?.let {
    expenseDocumentType.containsMatchIn(it.replace('\u00a0', ' ').trim())
} ?: false

/** Detects unusually large complete expense documents for one SKU using all other eligible documents. */
class SaleAnomalyDetector {
    fun detect(
        sales: List<PlanningSale>,
        from: YearMonth,
        through: YearMonth,
        multiplier: BigDecimal,
    ): SaleAnomalyDetection {
        require(multiplier > BigDecimal.ONE) { "Множитель аномального заказа должен быть больше 1." }
        require(from <= through) { "Начало периода проверки не может быть позднее окончания." }

        val documents = mutableMapOf<DocumentKey, DocumentQuantity>()
        var missingDateRows = 0
        var missingNumberRows = 0
        sales.forEach { sale ->
            if (!isExpenseSale(sale)) return@forEach
            val date = sale.date
            if (date == null) {
                missingDateRows++
                return@forEach
            }
            if (YearMonth.from(date) !in from..through) return@forEach
            val number = sale.documentNumber?.trim()?.takeIf { it.isNotEmpty() }
            if (number == null) {
                missingNumberRows++
                return@forEach
            }
            val document = documents.getOrPut(DocumentKey(date, number)) { DocumentQuantity() }
            if (sale.quantity == null) document.incomplete = true
            else document.quantity = document.quantity.add(sale.quantity)
        }

        val warnings = mutableListOf("ID клиента отсутствует: аномальные заказы сравниваются по расходным документам, а не по покупателям.")
        if (missingDateRows > 0) warnings.add("Расходные строки без даты исключены из проверки аномалий: $missingDateRows.")
        if (missingNumberRows > 0) warnings.add("Расходные строки без номера документа исключены из проверки аномалий: $missingNumberRows.")
        val incompleteCount = documents.values.count { it.incomplete }
        if (incompleteCount > 0) {
            warnings.add("Расходные документы с неизвестным количеством хотя бы в одной строке исключены из проверки аномалий: $incompleteCount.")
        }
        val eligible = documents.entries.filter { !it.value.incomplete && it.value.quantity.signum() > 0 }
            .sortedWith(compareBy({ it.key.date }, { it.key.number }))
        if (eligible.size < MINIMUM_DOCUMENTS) {
            warnings.add("Для проверки аномалий нужно минимум $MINIMUM_DOCUMENTS полных расходных документов с положительным итогом; найдено ${eligible.size}.")
            return SaleAnomalyDetection(emptyList(), warnings)
        }

        // Exact sums and cross-multiplication decide membership. Rounded statistics never affect detection.
        val total = eligible.fold(BigDecimal.ZERO) { sum, entry -> sum.add(entry.value.quantity) }
        val comparisonOrderCount = eligible.size - 1
        val divisor = comparisonOrderCount.toBigDecimal()
        val anomalies = eligible.mapNotNull { (key, document) ->
            val otherTotal = total.subtract(document.quantity)
            val thresholdNumerator = multiplier.multiply(otherTotal)
            if (document.quantity.multiply(divisor) <= thresholdNumerator) return@mapNotNull null
            SaleAnomaly(
                documentDate = key.date,
                documentNumber = key.number,
                quantity = document.quantity,
                comparisonOrderCount = comparisonOrderCount,
                averageOtherQuantity = otherTotal.divide(divisor, MathContext.DECIMAL128),
                thresholdQuantity = thresholdNumerator.divide(divisor, MathContext.DECIMAL128),
            )
        }
        return SaleAnomalyDetection(anomalies, warnings)
    }

    private data class DocumentKey(val date: LocalDate, val number: String)

    private class DocumentQuantity {
        var quantity: BigDecimal = BigDecimal.ZERO
        var incomplete: Boolean = false
    }

    private companion object {
        const val MINIMUM_DOCUMENTS = 5
    }
}
