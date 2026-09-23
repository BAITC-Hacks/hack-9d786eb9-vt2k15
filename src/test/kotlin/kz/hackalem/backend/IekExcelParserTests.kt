package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IekExcelParserTests {
    private val parser = IekExcelParser()

    @Test
    fun `monthly stock periods and final rows are discovered from the workbook`() {
        val book = book("Лист_1", mapOf(
            1 to mapOf("A" to "Номенклатура", "B" to "Ед.", "C" to "Номенклатура.Код", "D" to "дек. 2028", "E" to "янв. 2029", "F" to "Итого"),
            2 to mapOf("D" to "Количество", "E" to "Количество", "F" to "Количество"),
            3 to mapOf("D" to "нач. остаток", "E" to "нач. остаток", "F" to "нач. остаток"),
            4 to mapOf("A" to "Кабель", "B" to "м", "C" to "0001_", "D" to "-2.5", "F" to "-2.5"),
            42 to mapOf("A" to "Новый товар", "C" to "0002_", "D" to "0", "E" to "3"),
            100 to mapOf("A" to "Итого", "D" to "-2.5", "E" to "3", "F" to "-2.5"),
        ))

        val result = parser.parseMonthlyStocks(book)

        assertEquals(listOf(4, 42), result.rows.map { it.sourceRow })
        assertEquals(listOf(YearMonth.of(2028, 12), YearMonth.of(2029, 1)), result.rows.first().openingStocks.map { it.month })
        assertEquals(BigDecimal("-2.5"), result.rows.first().openingStocks.first().value)
        assertNull(result.rows.first().openingStocks.last().value)
        assertEquals(BigDecimal.ZERO, result.rows.last().openingStocks.first().value)
        assertEquals("0001_", result.rows.first().productCode)
        assertEquals(100, result.totals.single().sourceRow)
        assertEquals(BigDecimal("-2.5"), result.rows.first().periodOpeningStock)
    }

    @Test
    fun `monthly sales preserve missing quantities instead of replacing them with zero`() {
        val book = book("Лист_1", mapOf(
            1 to mapOf("A" to "Номенклатура", "B" to "Номенклатура.Код", "C" to "апр. 2030", "D" to "май 2030", "E" to "Итого"),
            2 to mapOf("C" to "Количество", "D" to "Количество", "E" to "Количество"),
            3 to mapOf("A" to "Товар без кода", "D" to "0", "E" to "0"),
            8 to mapOf("A" to "Итого", "C" to "0", "D" to "0", "E" to "0"),
        ))

        val result = parser.parseMonthlySales(book)

        assertEquals(1, result.rows.size)
        assertNull(result.rows.single().productCode)
        assertNull(result.rows.single().sales.first().value)
        assertEquals(BigDecimal.ZERO, result.rows.single().sales.last().value)
        assertEquals(YearMonth.of(2030, 5), result.rows.single().sales.last().month)
        assertEquals(8, result.totals.single().sourceRow)
    }

    @Test
    fun `incoming shipment metadata distinguishes document date and arrival deadline`() {
        val firstHeader = "РФ УТ-9000 от 31 декабря 2028\u00a0г. (поступление до 15.01.2029)"
        val book = book("Лист4", mapOf(
            1 to mapOf("A" to "Код 1с", "B" to "Артикул ИЭК", "C" to " Наименование", "D" to firstHeader, "E" to "УТ-9001 от 02.01.2029 (поступление до 31.01.2029)"),
            12 to mapOf("B" to "000-ARTICLE", "C" to "Товар без кода 1С", "E" to "-1.5"),
        ))

        val result = parser.parseIncomingShipments(book)

        assertEquals(2, result.shipments.size)
        assertEquals(firstHeader, result.shipments.first().sourceHeader)
        assertEquals("РФ", result.shipments.first().documentPrefix)
        assertEquals("УТ-9000", result.shipments.first().orderNumber)
        assertEquals(LocalDate.of(2028, 12, 31), result.shipments.first().orderDate)
        assertEquals(LocalDate.of(2029, 1, 15), result.shipments.first().expectedBy)
        assertEquals(LocalDate.of(2029, 1, 2), result.shipments.last().orderDate)
        assertNull(result.rows.single().productCode)
        assertNull(result.rows.single().quantities.first().quantity)
        assertEquals(BigDecimal("-1.5"), result.rows.single().quantities.last().quantity)
        assertTrue(book.issues.isEmpty())
    }

    @Test
    fun `all seasonality blocks retain years totals notes and rows with invalid months`() {
        val book = book("Сезонность", mapOf(
            3 to mapOf("A" to "год", "B" to "янв", "C" to "фев", "D" to "ИТОГО"),
            4 to mapOf("A" to "2028", "B" to "100", "C" to "200", "D" to "300"),
            5 to mapOf("A" to "2029", "B" to "120", "D" to "120"),
            10 to mapOf("B" to "Месяц", "C" to "Продажи 2028", "D" to "Коэф. сезонности", "E" to "Доля в году", "F" to "Продажи 2029", "G" to "Коэф. сезонности 2029", "H" to "Доля в году 2029", "I" to "СЕЗОННОСТЬ", "J" to "Год", "K" to "Среднемес.", "L" to "Итого за год"),
            11 to mapOf("B" to "янв", "C" to "100", "D" to "0.8", "E" to "0.3", "F" to "120", "G" to "1", "H" to "1", "I" to "0.95", "J" to "2028", "K" to "150", "L" to "300"),
            12 to mapOf("B" to "фев", "C" to "200", "D" to "1.2", "E" to "0.7", "F" to "0", "G" to "0", "H" to "0", "I" to "1.05", "J" to "2029", "K" to "120", "L" to "120"),
            13 to mapOf("B" to "Итого", "C" to "300", "D" to "1", "E" to "1", "F" to "120", "G" to "0.5", "H" to "1", "I" to "1"),
            26 to mapOf("B" to "СРЕДНЯЯ СЕЗОННОСТЬ 2028–2029"),
            27 to mapOf("B" to "Месяц", "C" to "Коэф. 2028", "D" to "Коэф. 2029", "E" to "Средний коэф.", "F" to "Норм. коэф.", "G" to "Доля в году"),
            28 to mapOf("B" to "янв", "C" to "0.8", "D" to "1", "E" to "0.9", "F" to "0.95", "G" to "0.1"),
            29 to mapOf("B" to "ошибочный месяц", "C" to "1.2", "D" to "1", "E" to "1.1", "F" to "1.05", "G" to "0.2", "H" to "коэф. 2029 — прогноз"),
            30 to mapOf("B" to "Итого", "F" to "1", "G" to "1"),
        ))

        val result = parser.parseSeasonality(book)

        assertEquals(listOf(2028, 2029), result.annualSales.map { it.year })
        assertNull(result.annualSales.last().monthlySales.last().value)
        assertEquals(listOf(2028, 2029), result.yearlySeasonality.first().years.map { it.year })
        assertEquals(BigDecimal.ZERO, result.yearlySeasonality.last().years.last().sales)
        assertEquals(listOf(2028, 2029), result.annualBases.map { it.year })
        assertEquals(BigDecimal("120"), result.annualBases.last().monthlyAverage)
        assertEquals(2, result.normalizedSeasonality.size)
        assertEquals("коэф. 2029 — прогноз", result.normalizedSeasonality.last().note)
        assertNull(result.normalizedSeasonality.last().month)
        assertEquals(listOf(2028, 2029), result.normalizedSeasonality.last().coefficients.map { it.year })
        assertEquals(13, result.yearlySeasonalityTotals.single().sourceRow)
        assertEquals(30, result.normalizationTotals.single().sourceRow)
        assertEquals("B29", book.issues.single().cell)
    }

    @Test
    fun `dynamics keep signed quantities textual identifiers and report malformed numbers`() {
        val book = book("Лист_1", mapOf(
            1 to mapOf("A" to "Дата", "B" to "Номер", "C" to "Документ", "D" to "Код", "E" to "Номенклатура", "F" to "Ед.", "G" to "Склад", "H" to "Количество"),
            2 to mapOf("A" to "04.01.2029 9:08:09", "B" to "000123", "C" to "Расходная накладная", "D" to "00001_", "E" to "Кабель", "F" to "м", "G" to "Алматы", "H" to "-2.5"),
            9 to mapOf("A" to "05.01.2029 9:08:09", "D" to "00001_", "H" to "не число"),
            15 to mapOf("A" to "Итого", "H" to "-2.5"),
        ))

        val result = parser.parseSalesDynamics(book)

        assertEquals(2, result.rows.size)
        assertEquals(LocalDateTime.of(2029, 1, 4, 9, 8, 9), result.rows.first().occurredAt)
        assertEquals("000123", result.rows.first().documentNumber)
        assertEquals("00001_", result.rows.first().productCode)
        assertEquals(BigDecimal("-2.5"), result.rows.first().quantity)
        assertNull(result.rows.last().quantity)
        assertEquals("H9", book.issues.single().cell)
        assertEquals(15, result.totals.single().sourceRow)
    }

    @Test
    fun `MOQ reads formula cache without evaluating formulas`() {
        val book = book("Лист7", mapOf(
            1 to mapOf("A" to "№", "B" to "Код 1с", "C" to "Артикул поставщика", "D" to "Наименование", "E" to "Мин. разр. к отгр."),
            2 to mapOf("A" to "1", "B" to "0001_", "C" to "ARTICLE", "D" to "Товар", "E" to "12"),
        ), mapOf("E2" to "VLOOKUP(C2,[1]Прайс!A:P,15,0)"))

        val result = parser.parseMoq(book)

        assertEquals(BigDecimal("12"), result.rows.single().minimumShipmentQuantity)
        assertEquals("0001_", result.rows.single().productCode)
        assertTrue(book.issues.isEmpty())
    }

    private fun book(
        sheetName: String,
        values: Map<Int, Map<String, String>>,
        formulas: Map<String, String> = emptyMap(),
    ): ExcelWorkbook {
        val fileName = "test.xlsx"
        val issues = mutableListOf<ExcelIssue>()
        val rows = values.entries.sortedBy { it.key }.map { (number, cells) ->
            ExcelRow(number, cells.mapValues { (column, value) -> ExcelCell(value, formulas["$column$number"]) }, fileName, sheetName, issues)
        }
        val sheet = ExcelSheet(sheetName, rows, "visible", fileName, issues)
        return ExcelWorkbook(fileName, mapOf(sheetName to sheet), issues)
    }
}
