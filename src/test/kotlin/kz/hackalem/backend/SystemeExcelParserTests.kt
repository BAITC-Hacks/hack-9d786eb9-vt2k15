package kz.hackalem.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Month
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemeExcelParserTests {
    private val parser = SystemeExcelParser()

    @Test
    fun `incoming fields follow headers after additional months and retain formulas and blank weight`() {
        val labels = listOf(
            "№", "Артикул поставщика", "Код 1с", "Наименование", "Категория 2027", "СС реал",
            "Январь 2027 г.", "Февраль 2027 г.", "Продажи 2026", "Ср мес 2026",
            "Сумма последние 12 мес", "   Ср мес за последние 12 мес", "Кэф. Роста", "Кэф. Сез-ти",
            "Витрина", "Остаток ТЗ", "РЦ ЕКТ  Рыскулова", "Розничный склад", "Остаток",
            "Зарезервировано", "Свободный остаток", "Запас", "Заказ", "СЭ в пути 24.09", "Вес",
        )
        val column = labels.withIndex().associate { it.value to letters(it.index + 1) }
        val header = column.entries.associate { (label, c) -> c to ExcelCell(label) }
        val data = linkedMapOf(
            "A" to ExcelCell("39"), "B" to ExcelCell("ATN000343"), "C" to ExcelCell("00300200428_"),
            "D" to ExcelCell("Товар"), "E" to ExcelCell("5"), "F" to ExcelCell("1050.61"),
            "G" to ExcelCell("-3"), "H" to ExcelCell("0"),
            column.getValue("Продажи 2026") to ExcelCell("30", "SUM(G41:H41)"),
            column.getValue("Ср мес 2026") to ExcelCell("2.5", "I41/12"),
            column.getValue("Сумма последние 12 мес") to ExcelCell("25", "SUM(G41:H41)"),
            column.getValue("   Ср мес за последние 12 мес") to ExcelCell("5", "K41/5"),
            column.getValue("Витрина") to ExcelCell("17"),
            column.getValue("РЦ ЕКТ  Рыскулова") to ExcelCell("0"),
            column.getValue("Заказ") to ExcelCell(null),
            column.getValue("СЭ в пути 24.09") to ExcelCell("120"),
        )
        val book = workbook("TDSheet" to mapOf(2 to header, 41 to data), "Лист1" to seasonalCells(2027))

        val parsed = parser.parseIncomingShipments(book)
        val row = parsed.rows.single()
        assertEquals(41, row.sourceRow)
        assertEquals("00300200428_", row.productCode)
        assertEquals(SystemeCategory(2027, "5"), row.category)
        assertEquals(listOf(YearMonth.of(2027, 1), YearMonth.of(2027, 2)), row.monthlySales.map { it.month })
        assertEquals(BigDecimal("-3"), row.monthlySales.first().value)
        assertEquals(BigDecimal.ZERO, row.monthlySales.last().value)
        assertEquals(2026, row.annualSales.single().year)
        assertEquals(BigDecimal("5"), row.reportedAverageMonthlySales.value)
        assertEquals("K41/5", row.reportedAverageMonthlySales.formula)
        assertEquals(BigDecimal("17"), row.displayQuantity)
        assertEquals(BigDecimal.ZERO, row.ryskulovaDistributionStock)
        assertNull(row.proposedOrderQuantity)
        assertNull(row.weight)
        assertEquals("24.09", row.inTransit.single().dateLabel)
        assertEquals(BigDecimal("120"), row.inTransit.single().quantity)
        assertEquals(2027, parsed.hiddenSeasonality.annualSales.single().year)
        assertNull(parsed.hiddenSeasonality.rows.first().combinedSeasonalityIndex)
        assertTrue(book.issues.isEmpty())
    }

    @Test
    fun `monthly sales keep new months sparse rows totals and hidden seasonal blocks`() {
        val header = cells("A" to "Номенклатура", "B" to "Номенклатура.Код", "C" to "Артикул", "D" to "Кратность",
            "E" to "сент. 2027", "F" to "окт. 2027", "G" to "Итого")
        val grid = mapOf(
            1 to header, 2 to cells("E" to "Количество", "F" to "Количество", "G" to "Количество"),
            3 to cells("A" to "Первый", "B" to "0001_", "C" to "SKU", "D" to "0", "E" to "-2", "G" to "-2"),
            800 to cells("A" to "Без кода", "E" to "0", "F" to "3", "G" to "3"),
            900 to cells("A" to "Итого", "E" to "-2", "F" to "3", "G" to "1"),
        )
        val book = workbook("Лист_1" to grid, "Лист1" to seasonalCells(2027))

        val parsed = parser.parseMonthlySales(book)
        assertEquals(2, parsed.rows.size)
        assertEquals(BigDecimal.ZERO, parsed.rows.first().orderMultiple)
        assertNull(parsed.rows.first().monthlySales.last().value)
        assertEquals(800, parsed.rows.last().sourceRow)
        assertNull(parsed.rows.last().productCode)
        assertEquals(BigDecimal.ONE, parsed.totals.single().totalQuantity)
        assertEquals(YearMonth.of(2027, 10), parsed.totals.single().monthlySales.last().month)
        assertEquals(2, parsed.hiddenSeasonality.rows.size)
        assertEquals(1, parsed.hiddenSeasonality.annualSummaries.size)
        assertEquals(1, parsed.hiddenSeasonality.totals.size)
        assertTrue(book.issues.any { it.cell == "B800" })
    }

    @Test
    fun `seasonality keeps all typed blocks and incomplete labels instead of dropping values`() {
        val source = seasonalCells(2030, combined = true).mapValues { it.value.toMutableMap() }.toMutableMap()
        source.getValue(4)["A"] = ExcelCell("unknown year")
        source.getValue(11)["C"] = ExcelCell("bad number", "INDEX(B4:M4,1)")
        source.getValue(11)["M"] = ExcelCell(null, "A4")
        source.getValue(12)["B"] = ExcelCell("unknown month", "INDEX(B3:M3,2)")
        val book = workbook("Лист1" to source)

        val parsed = parser.parseSeasonality(book)
        assertEquals(2, parsed.rows.size)
        assertEquals(Month.JANUARY, parsed.rows.first().month)
        assertNull(parsed.rows.last().month)
        assertEquals("unknown month", parsed.rows.last().sourceMonthLabel.value)
        assertEquals("INDEX(B3:M3,2)", parsed.rows.last().sourceMonthLabel.formula)
        assertNull(parsed.rows.first().years.single().sales.value)
        assertEquals("INDEX(B4:M4,1)", parsed.rows.first().years.single().sales.formula)
        assertNotNull(parsed.rows.first().combinedSeasonalityIndex)
        assertNull(parsed.annualSales.single().year)
        assertEquals(12, parsed.annualSales.single().monthlySales.size)
        assertNull(parsed.annualSales.single().monthlySales[2].value)
        assertNull(parsed.annualSummaries.single().year.value)
        assertEquals("A4", parsed.annualSummaries.single().year.formula)
        assertEquals(2030, parsed.corrections.single().numeratorYear)
        assertEquals(2029, parsed.corrections.single().denominatorYear)
        assertEquals("янв-сен", parsed.corrections.single().periodLabel)
        assertEquals(BigDecimal("1.2"), parsed.corrections.single().factor.value)
        assertEquals(1, parsed.totals.size)
        assertTrue(book.issues.map { it.cell }.containsAll(listOf("A4", "C11", "M11", "B12")))
    }

    private fun seasonalCells(year: Int, combined: Boolean = false): Map<Int, Map<String, ExcelCell>> {
        val months = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
        val annualHeader = cells("A" to "год", "N" to "ИТОГО").toMutableMap()
        months.forEachIndexed { index, name -> annualHeader[letters(index + 2)] = ExcelCell(name) }
        val detailHeader = cells("B" to "Месяц", "C" to "Продажи $year", "D" to "Коэф. сезонности", "E" to "Доля в году",
            "M" to "Год", "N" to "Среднемес.", "O" to "Итого за год").toMutableMap()
        if (combined) detailHeader["L"] = ExcelCell("СЕЗОННОСТЬ")
        val first = cells("B" to "янв", "C" to "10", "D" to "0.5", "E" to "0.333", "M" to "$year", "N" to "15", "O" to "30").toMutableMap()
        val second = cells("B" to "фев", "C" to "20", "D" to "1.5", "E" to "0.667").toMutableMap()
        if (combined) { first["L"] = ExcelCell("0.75", "AVERAGE(D11,D11)"); second["L"] = ExcelCell("1.25", "AVERAGE(D12,D12)") }
        val result = linkedMapOf(
            3 to annualHeader, 4 to cells("A" to "$year", "B" to "10", "C" to "20", "N" to "30"),
            8 to cells("B" to "Сезонность по годам"), 10 to detailHeader, 11 to first, 12 to second,
            23 to cells("B" to "Итого", "C" to "30", "D" to "1", "E" to "1"),
        )
        if (combined) result[15] = mapOf("M" to ExcelCell("Поправка $year/${year - 1} (янв-сен)"), "N" to ExcelCell("1.2", "AVERAGE(D11:D12)"))
        return result
    }

    private fun workbook(vararg definitions: Pair<String, Map<Int, Map<String, ExcelCell>>>): ExcelWorkbook {
        val issues = mutableListOf<ExcelIssue>()
        val name = "test.xlsx"
        val sheets = definitions.associate { (sheetName, grid) ->
            val rows = grid.toSortedMap().map { (number, cells) -> ExcelRow(number, cells, name, sheetName, issues) }
            sheetName to ExcelSheet(sheetName, rows, if (definitions.size > 1 && sheetName == "Лист1") "hidden" else "visible", name, issues)
        }
        return ExcelWorkbook(name, sheets, issues)
    }

    private fun cells(vararg entries: Pair<String, String>): Map<String, ExcelCell> = entries.associate { (column, text) -> column to ExcelCell(text) }

    private fun letters(column: Int): String = if (column <= 26) ('A' + column - 1).toString()
        else letters((column - 1) / 26) + ('A' + (column - 1) % 26)
}
