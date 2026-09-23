package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

@Component
class IekExcelParser {
    fun parseMoq(book: ExcelWorkbook): IekMoqData {
        val sheet = book.sheet("Лист7")
        sheet.requireHeaders(1, mapOf(
            "A" to "№", "B" to "Код 1с", "C" to "Артикул поставщика",
            "D" to "Наименование", "E" to "Мин. разр. к отгр.",
        ))
        return IekMoqData(book.fileName, sheet.dataRows(2).filterNot { it.isTotal("A") }.map {
            IekMoqRow(it.number, it.integer("A"), it.text("B"), it.text("C"), it.text("D"), it.decimal("E"))
        })
    }

    fun parseSalesDynamics(book: ExcelWorkbook): IekSalesDynamicsData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf(
            "A" to "Дата", "B" to "Номер", "C" to "Документ", "D" to "Код",
            "E" to "Номенклатура", "F" to "Ед.", "G" to "Склад", "H" to "Количество",
        ))
        val (totals, rows) = sheet.dataRows(2).partition { it.isTotal("A") }
        return IekSalesDynamicsData(book.fileName, rows.map {
            IekSalesDynamicsRow(
                it.number, it.dateTime("A"), it.text("B"), it.text("C"), it.text("D"),
                it.text("E"), it.text("F"), it.text("G"), it.decimal("H"),
            )
        }, totals.map { IekSalesDynamicsTotal(it.number, it.decimal("H")) })
    }

    fun parseMonthlyStocks(book: ExcelWorkbook): IekMonthlyStocksData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "Номенклатура", "B" to "Ед.", "C" to "Номенклатура.Код"))
        val months = monthColumns(sheet, 1)
        if (months.isEmpty()) throw ExcelFormatException("${book.fileName}: не найдены месяцы остатков")
        sheet.requireHeaders(2, months.associate { it.column to "Количество" })
        sheet.requireHeaders(3, months.associate { it.column to "нач. остаток" })
        val totalColumn = sheet.row(1).columnWithHeader("Итого")
        val (totals, rows) = sheet.dataRows(4).partition { it.isTotal("A") }
        return IekMonthlyStocksData(book.fileName, rows.map {
            IekMonthlyStockRow(
                it.number, it.text("A"), it.text("B"), it.text("C"),
                it.monthlyValues(months), it.decimalOrNull(totalColumn),
            )
        }, totals.map {
            IekMonthlyStockTotal(it.number, it.monthlyValues(months), it.decimalOrNull(totalColumn))
        })
    }

    fun parseMonthlySales(book: ExcelWorkbook): IekMonthlySalesData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "Номенклатура", "B" to "Номенклатура.Код"))
        val months = monthColumns(sheet, 1)
        if (months.isEmpty()) throw ExcelFormatException("${book.fileName}: не найдены месяцы продаж")
        sheet.requireHeaders(2, months.associate { it.column to "Количество" })
        val totalColumn = sheet.row(1).columnWithHeader("Итого")
        val (totals, rows) = sheet.dataRows(3).partition { it.isTotal("A") }
        return IekMonthlySalesData(book.fileName, rows.map {
            IekMonthlySalesRow(it.number, it.text("A"), it.text("B"), it.monthlyValues(months), it.decimalOrNull(totalColumn))
        }, totals.map {
            IekMonthlySalesTotal(it.number, it.monthlyValues(months), it.decimalOrNull(totalColumn))
        })
    }

    fun parseIncomingShipments(book: ExcelWorkbook): IekIncomingShipmentsData {
        val sheetName = "Лист4"
        val sheet = book.sheet(sheetName)
        sheet.requireHeaders(1, mapOf("A" to "Код 1с", "B" to "Артикул ИЭК", "C" to "Наименование"))
        val columns = sheet.rows.asSequence().flatMap { it.cells.asSequence() }
            .filter { columnNumber(it.key) > 3 && it.value.hasContent() }
            .map { it.key }.distinct().sortedBy(::columnNumber).toList()
        val shipments = columns.map { parseShipmentHeader(book, sheetName, it, sheet.row(1).text(it)) }
        val (totals, rows) = sheet.dataRows(2).partition { it.isTotal("A") }
        fun quantities(row: ExcelRow) = shipments.map { IekShipmentQuantity(it.sourceColumn, row.decimal(it.sourceColumn)) }
        return IekIncomingShipmentsData(book.fileName, shipments, rows.map {
            IekIncomingShipmentRow(it.number, it.text("A"), it.text("B"), it.text("C"), quantities(it))
        }, totals.map { IekIncomingShipmentTotal(it.number, quantities(it)) })
    }

    fun parseSeasonality(book: ExcelWorkbook): IekSeasonalityData {
        val sheetName = "Сезонность"
        val sheet = book.sheet(sheetName)
        val annualHeader = sheet.rows.firstOrNull { normalize(it.text("A")) == "год" }
            ?: throw ExcelFormatException("${book.fileName}: не найдена исходная таблица сезонности")
        val yearlyHeader = sheet.rows.firstOrNull { row ->
            normalize(row.text("B")) == "месяц" && row.cells.values.any { salesYearPattern.matches(normalize(it.raw)) }
        } ?: throw ExcelFormatException("${book.fileName}: не найдена таблица сезонности по годам")
        val normalizedHeader = sheet.rows.firstOrNull {
            normalize(it.text("B")) == "месяц" && it.columnWithHeader("Норм. коэф.") != null
        } ?: throw ExcelFormatException("${book.fileName}: не найден блок нормализации сезонности")

        val annualMonthColumns = annualHeader.cells.keys.mapNotNull { column ->
            monthNumber(annualHeader.text(column))?.let { column to it }
        }.sortedBy { columnNumber(it.first) }
        if (annualMonthColumns.isEmpty()) throw ExcelFormatException("${book.fileName}: в исходной сезонности отсутствуют месяцы")
        val annualTotalColumn = annualHeader.columnWithHeader("ИТОГО")
        val annualSectionEnd = sheet.rows.firstOrNull { row ->
            row.number > annualHeader.number && row.number < yearlyHeader.number &&
                row.cells.values.any { normalize(it.raw) == "сезонность по годам" }
        }?.number ?: yearlyHeader.number
        val annualValueColumns = annualMonthColumns.map { it.first } + listOfNotNull(annualTotalColumn)
        val annualRows = sheet.rows.filter { row ->
            row.number > annualHeader.number && row.number < annualSectionEnd &&
                (row.cells["A"]?.hasContent() == true || annualValueColumns.any { row.cells[it]?.hasContent() == true })
        }
        val annualSales = annualRows.filterNot { it.isTotal("A") }.map { row ->
            IekAnnualSalesRow(row.number, row.integer("A"), annualMonthColumns.map { (column, month) ->
                IekCalendarMonthValue(month, row.decimal(column))
            }, row.decimalOrNull(annualTotalColumn))
        }

        val yearGroups = yearlyHeader.cells.entries.mapNotNull { (column, _) ->
            salesYearPattern.matchEntire(normalize(yearlyHeader.text(column)))?.let {
                YearGroup(it.groupValues[1].toInt(), column, nextColumn(column), nextColumn(column, 2))
            }
        }.sortedBy { columnNumber(it.salesColumn) }
        val normalizedColumn = yearlyHeader.columnWithHeader("СЕЗОННОСТЬ")
        val yearlyCandidates = sheet.rows.filter { it.number > yearlyHeader.number && it.number < normalizedHeader.number }
        val yearlyValueColumns = yearGroups.flatMap { listOf(it.salesColumn, it.coefficientColumn, it.shareColumn) } + listOfNotNull(normalizedColumn)
        val yearlyRows = yearlyCandidates.filter { row ->
            !row.isTotal("B") && (monthNumber(row.text("B")) != null || yearlyValueColumns.any { row.cells[it]?.hasContent() == true })
        }
        val yearlyTotals = yearlyCandidates.filter { it.isTotal("B") }
        fun yearValues(row: ExcelRow) = yearGroups.map {
            IekSeasonalityYearValues(it.year, row.decimal(it.salesColumn), row.decimal(it.coefficientColumn), row.decimal(it.shareColumn))
        }
        val yearlySeasonality = yearlyRows.map { row ->
            IekYearlySeasonalityRow(row.number, row.text("B"), readMonth(book, sheetName, row), yearValues(row), row.decimalOrNull(normalizedColumn))
        }
        val yearlySeasonalityTotals = yearlyTotals.map { row ->
            IekYearlySeasonalityTotal(row.number, yearValues(row).map {
                IekSeasonalityYearTotal(it.year, it.sales, it.coefficient, it.annualShare)
            }, row.decimalOrNull(normalizedColumn))
        }

        val yearColumn = yearlyHeader.requiredHeader("Год", book.fileName)
        val averageColumn = yearlyHeader.requiredHeader("Среднемес.", book.fileName)
        val baseTotalColumn = yearlyHeader.requiredHeader("Итого за год", book.fileName)
        val annualBases = yearlyCandidates.filter { row ->
            listOf(yearColumn, averageColumn, baseTotalColumn).any { row.cells[it]?.hasContent() == true }
        }.map {
            IekAnnualSeasonalityBaseRow(it.number, it.integer(yearColumn), it.decimal(averageColumn), it.decimal(baseTotalColumn))
        }

        val coefficientColumns = normalizedHeader.cells.keys.mapNotNull { column ->
            coefficientYearPattern.matchEntire(normalize(normalizedHeader.text(column)))?.let {
                column to it.groupValues[1].toInt()
            }
        }.sortedBy { columnNumber(it.first) }
        if (coefficientColumns.isEmpty()) throw ExcelFormatException("${book.fileName}: не найдены годовые коэффициенты нормализации")
        val averageCoefficientColumn = normalizedHeader.requiredHeader("Средний коэф.", book.fileName)
        val normalizedCoefficientColumn = normalizedHeader.requiredHeader("Норм. коэф.", book.fileName)
        val shareColumn = normalizedHeader.requiredHeader("Доля в году", book.fileName)
        val noteColumn = nextColumn(shareColumn)
        val normalizedCandidates = sheet.rows.filter { it.number > normalizedHeader.number }
        val normalizedValueColumns = coefficientColumns.map { it.first } + listOf(averageCoefficientColumn, normalizedCoefficientColumn, shareColumn, noteColumn)
        val normalizedRows = normalizedCandidates.filter { row ->
            !row.isTotal("B") && (monthNumber(row.text("B")) != null || normalizedValueColumns.any { row.cells[it]?.hasContent() == true })
        }
        val normalizationTotals = normalizedCandidates.filter { it.isTotal("B") }.map {
            IekSeasonalityNormalizationTotal(it.number, it.decimal(normalizedCoefficientColumn), it.decimal(shareColumn))
        }
        val normalizedSeasonality = normalizedRows.map { row ->
            IekNormalizedSeasonalityRow(
                row.number, row.text("B"), readMonth(book, sheetName, row),
                coefficientColumns.map { (column, year) -> IekSeasonalityCoefficient(year, row.decimal(column)) },
                row.decimal(averageCoefficientColumn), row.decimal(normalizedCoefficientColumn),
                row.decimal(shareColumn), row.text(noteColumn),
            )
        }
        return IekSeasonalityData(
            book.fileName, annualSales, yearlySeasonality, yearlySeasonalityTotals,
            annualBases, normalizedSeasonality, normalizationTotals,
        )
    }

    private fun parseShipmentHeader(book: ExcelWorkbook, sheet: String, column: String, header: String?): IekShipmentColumn {
        val text = normalize(header)
        val orderMatch = Regex("^(.+?)\\s+от\\s+(.+?)(?:\\s*\\(|$)").find(text)
        val beforeDate = orderMatch?.groupValues?.get(1)
        val orderNumber = beforeDate?.substringAfterLast(' ')
        val prefix = beforeDate?.substringBeforeLast(' ', "")?.ifBlank { null }
        val rawOrderDate = orderMatch?.groupValues?.get(2)
        val orderDate = rawOrderDate?.let(::russianDate)
        val expectedMatch = Regex("поступление\\s+до\\s+(\\d{1,2}\\.\\d{1,2}\\.\\d{4})").find(text)
        val expectedBy = expectedMatch?.groupValues?.get(1)?.let(::russianDate)
        if (orderNumber == null || orderDate == null) {
            book.issue(sheet, 1, column, "Не удалось определить номер или дату заказа из заголовка партии", header)
        }
        if (expectedBy == null) {
            book.issue(sheet, 1, column, "Не удалось определить срок «поступление до» из заголовка партии", header)
        }
        // Header parsing is case-insensitive; identifiers/prefixes keep their source spelling.
        val originalBeforeDate = Regex("^(.+?)\\s+от\\s+", RegexOption.IGNORE_CASE)
            .find(header?.replace('\u00a0', ' ')?.trim().orEmpty())?.groupValues?.get(1)
        return IekShipmentColumn(
            column, header,
            originalBeforeDate?.substringBeforeLast(' ', "")?.ifBlank { null } ?: prefix,
            originalBeforeDate?.substringAfterLast(' ') ?: orderNumber, orderDate, expectedBy,
        )
    }

    private fun russianDate(value: String): LocalDate? {
        val numeric = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").find(value.trim())
        val words = Regex("^(\\d{1,2})\\s+([а-яё.]+)\\s+(\\d{4})").find(normalize(value))
        val day = (numeric ?: words)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val month = numeric?.groupValues?.get(2)?.toIntOrNull() ?: monthNumber(words?.groupValues?.get(2)) ?: return null
        val year = (numeric ?: words)?.groupValues?.get(3)?.toIntOrNull() ?: return null
        return try { LocalDate.of(year, month, day) } catch (_: DateTimeException) { null }
    }

    private fun monthNumber(value: String?): Int? {
        val month = normalize(value).trimEnd('.')
        return when {
            month.startsWith("янв") -> 1
            month.startsWith("фев") -> 2
            month.startsWith("мар") -> 3
            month.startsWith("апр") -> 4
            month == "май" || month == "мая" -> 5
            month.startsWith("июн") -> 6
            month.startsWith("июл") -> 7
            month.startsWith("авг") -> 8
            month.startsWith("сен") -> 9
            month.startsWith("окт") -> 10
            month.startsWith("ноя") -> 11
            month.startsWith("дек") -> 12
            else -> null
        }
    }

    private fun readMonth(book: ExcelWorkbook, sheet: String, row: ExcelRow): Int? =
        monthNumber(row.text("B")).also { month ->
            if (month == null) book.issue(sheet, row.number, "B", "Не удалось определить месяц сезонности", row.cells["B"]?.raw)
        }

    private fun ExcelSheet.dataRows(start: Int) = rows.filter { it.number >= start && it.cells.values.any { cell -> cell.hasContent() } }
    private fun ExcelCell.hasContent() = !raw.isNullOrBlank() || formula != null || error != null
    private fun ExcelRow.isTotal(column: String) = normalize(text(column)) == "итого"
    private fun ExcelRow.decimalOrNull(column: String?): BigDecimal? = column?.let { decimal(it) }
    private fun ExcelRow.columnWithHeader(header: String): String? = cells.keys.firstOrNull { normalize(text(it)) == normalize(header) }
    private fun ExcelRow.requiredHeader(header: String, fileName: String): String =
        columnWithHeader(header) ?: throw ExcelFormatException("$fileName: в строке $number отсутствует поле «$header»")
    private fun normalize(value: String?) = value.orEmpty().replace('\u00a0', ' ').trim().replace(Regex("\\s+"), " ").lowercase()
    private fun columnNumber(column: String) = column.fold(0) { value, char -> value * 26 + (char - 'A' + 1) }
    private fun nextColumn(column: String, offset: Int = 1): String {
        var number = columnNumber(column) + offset
        val result = StringBuilder()
        while (number > 0) {
            number--
            result.append('A' + number % 26)
            number /= 26
        }
        return result.reverse().toString()
    }

    private data class YearGroup(val year: Int, val salesColumn: String, val coefficientColumn: String, val shareColumn: String)

    private companion object {
        val salesYearPattern = Regex("продажи\\s+(\\d{4})")
        val coefficientYearPattern = Regex("коэф\\.?\\s+(\\d{4})")
    }
}
