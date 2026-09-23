package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.time.Month

@Component
class SystemeExcelParser {
    fun parseMoq(book: ExcelWorkbook): SystemeMoqData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "№", "B" to "Номенклатура", "C" to "Номенклатура.Код", "D" to "Артикул", "E" to "Кратность"))
        val rows = productRows(book, sheet, "Лист_1", 3, "C", "B").map { row ->
            SystemeMoqRow(row.number, row.integer("A"), row.text("B"), row.text("C"), row.text("D"), row.decimal("E"))
        }
        return SystemeMoqData(book.fileName, rows)
    }

    fun parseSalesDynamics(book: ExcelWorkbook): SystemeSalesDynamicsData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "Дата", "B" to "Номер", "C" to "Документ", "D" to "Код", "E" to "Номенклатура", "F" to "Ед.", "G" to "Склад", "H" to "Количество"))
        val rows = productRows(book, sheet, "Лист_1", 2, "D", "A").map { row ->
            SystemeSalesDynamicsRow(
                row.number, row.dateTime("A"), row.text("B"), row.text("C"), row.text("D"),
                row.text("E"), row.text("F"), row.text("G"), row.decimal("H"),
            )
        }
        val totals = sheet.rows.filter { isTotal(it.text("A")) }.map { SystemeSalesDynamicsTotal(it.number, it.decimal("H")) }
        return SystemeSalesDynamicsData(book.fileName, rows, totals)
    }

    fun parseMonthlyStocks(book: ExcelWorkbook): SystemeMonthlyStocksData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "№", "B" to "Номенклатура", "C" to "Номенклатура.Код", "D" to "Ед.изм"))
        val months = requireMonths(sheet, 1)
        val rows = productRows(book, sheet, "Лист_1", 4, "C", "B").map { row ->
            SystemeMonthlyStockRow(row.number, row.integer("A"), row.text("B"), row.text("C"), row.text("D"), row.monthlyValues(months))
        }
        return SystemeMonthlyStocksData(book.fileName, rows)
    }

    fun parseMonthlySales(book: ExcelWorkbook): SystemeMonthlySalesData {
        val sheet = book.sheet("Лист_1")
        sheet.requireHeaders(1, mapOf("A" to "Номенклатура", "B" to "Номенклатура.Код", "C" to "Артикул", "D" to "Кратность"))
        val months = requireMonths(sheet, 1)
        val totalColumn = headerColumns(sheet.row(1)).required("Итого")
        sheet.requireHeaders(2, (months.map { it.column } + totalColumn).associateWith { "Количество" })
        val rows = productRows(book, sheet, "Лист_1", 3, "B", "A").map { row ->
            SystemeMonthlySalesRow(
                row.number, row.text("A"), row.text("B"), row.text("C"), row.decimal("D"),
                row.monthlyValues(months), row.decimal(totalColumn),
            )
        }
        val totals = sheet.rows.filter { isTotal(it.text("A")) }.map {
            SystemeMonthlySalesTotal(it.number, it.monthlyValues(months), it.decimal(totalColumn))
        }
        return SystemeMonthlySalesData(book.fileName, rows, totals, parseSeasonalReport(book, "Лист1"))
    }

    fun parseIncomingShipments(book: ExcelWorkbook): SystemeIncomingShipmentsData {
        val sheet = book.sheet("TDSheet")
        sheet.requireHeaders(2, mapOf("A" to "№", "B" to "Артикул поставщика", "C" to "Код 1с", "D" to "Наименование"))
        val headers = headerColumns(sheet.row(2))
        val months = requireMonths(sheet, 2)
        val categoryHeader = headers.singleMatching(Regex("Категория\\s+(\\d{4})"), "Категория <год>")
        val categoryYear = requireNotNull(Regex("\\d{4}").find(categoryHeader.label)).value.toInt()
        val annualTotals = headers.yearColumns(Regex("Продажи\\s+(\\d{4})"))
        val annualAverages = headers.yearColumns(Regex("Ср\\s*мес\\s+(\\d{4})"))
        val years = (annualTotals.keys + annualAverages.keys).sorted()
        val ssReal = headers.required("СС реал")
        val rollingSales = headers.required("Сумма последние 12 мес")
        val rollingAverage = headers.required("Ср мес за последние 12 мес")
        val growth = headers.required("Кэф. Роста")
        val seasonal = headers.required("Кэф. Сез-ти")
        val display = headers.required("Витрина")
        val tz = headers.required("Остаток ТЗ")
        val ryskulova = headers.required("РЦ ЕКТ Рыскулова")
        val retail = headers.required("Розничный склад")
        val stock = headers.required("Остаток")
        val reserved = headers.required("Зарезервировано")
        val available = headers.required("Свободный остаток")
        val coverage = headers.required("Запас")
        val proposedOrder = headers.required("Заказ")
        val weight = headers.required("Вес")
        val transitHeaders = headers.filter { normalized(it.label).startsWith("сэ в пути") }
        require(transitHeaders.isNotEmpty()) { "${book.fileName}: TDSheet: отсутствует колонка СЭ в пути" }

        val rows = productRows(book, sheet, "TDSheet", 3, "C", "D").map { row ->
            SystemeIncomingShipmentRow(
                sourceRow = row.number,
                reportNumber = row.integer("A"),
                supplierArticle = row.text("B"),
                productCode = row.text("C"),
                productName = row.text("D"),
                category = SystemeCategory(categoryYear, row.text(categoryHeader.column)),
                ssReal = row.decimal(ssReal),
                monthlySales = row.monthlyValues(months),
                annualSales = years.map { year ->
                    SystemeAnnualProductSales(year, annualTotals[year]?.let { row.calculated(it) }, annualAverages[year]?.let { row.calculated(it) })
                },
                reportedRollingSales = row.calculated(rollingSales),
                reportedAverageMonthlySales = row.calculated(rollingAverage),
                growthChange = row.calculated(growth),
                seasonalityChange = row.calculated(seasonal),
                displayQuantity = row.decimal(display),
                tzStock = row.decimal(tz),
                ryskulovaDistributionStock = row.decimal(ryskulova),
                retailWarehouseStock = row.decimal(retail),
                stock = row.decimal(stock),
                reservedStock = row.decimal(reserved),
                availableStock = row.decimal(available),
                stockCoverageMonths = row.calculated(coverage),
                proposedOrderQuantity = row.decimal(proposedOrder),
                inTransit = transitHeaders.map { header ->
                    SystemeInTransitQuantity(header.label, header.label.replace(Regex("(?i)^СЭ\\s+в\\s+пути\\s*"), "").trim().ifBlank { null }, row.decimal(header.column))
                },
                weight = row.decimal(weight),
            )
        }
        return SystemeIncomingShipmentsData(book.fileName, rows, parseSeasonalReport(book, "Лист1"))
    }

    fun parseSeasonality(book: ExcelWorkbook): SystemeSeasonalityData {
        val report = parseSeasonalReport(book, "Лист1")
        return SystemeSeasonalityData(book.fileName, report.rows, report.annualSales, report.annualSummaries, report.totals, report.corrections)
    }

    private fun parseSeasonalReport(book: ExcelWorkbook, sheetName: String): SystemeSeasonalityReport {
        val sheet = book.sheet(sheetName)
        val annualHeaderRow = sheet.rows.firstOrNull { normalized(it.text("A")) == "год" }
            ?: throw IllegalArgumentException("${book.fileName}: $sheetName: отсутствует верхний заголовок год")
        val monthlyHeaderRow = sheet.rows.firstOrNull { normalized(it.text("B")) == "месяц" }
            ?: throw IllegalArgumentException("${book.fileName}: $sheetName: отсутствует заголовок Месяц")
        val annualHeaders = headerColumns(annualHeaderRow)
        val annualMonths = annualHeaders.mapNotNull { h -> russianMonth(h.label)?.let { h.column to it } }
        require(annualMonths.isNotEmpty()) { "${book.fileName}: $sheetName: отсутствуют названия месяцев" }
        val annualTotal = annualHeaders.required("ИТОГО")
        val annualSales = sheet.rows.filter { row ->
            row.number > annualHeaderRow.number && row.number < monthlyHeaderRow.number &&
                (row.text("A") != null || row.hasContent(annualTotal) || annualMonths.any { (column, _) ->
                    row.cells[column]?.formula != null || row.cells[column]?.error != null || row.text(column)?.toBigDecimalOrNull() != null
                })
        }.map { row ->
            val year = row.integer("A")
            if (row.text("A") == null) book.issue(sheetName, row.number, "A", "Не указан год исходных месячных показателей")
            SystemeAnnualSalesRow(
                row.number, year, annualMonths.map { (column, month) -> SystemeCalendarMonthValue(month, row.decimal(column)) },
                row.calculated(annualTotal),
            )
        }

        val headers = headerColumns(monthlyHeaderRow)
        val yearGroups = seasonalYearColumns(headers)
        require(yearGroups.isNotEmpty()) { "${book.fileName}: $sheetName: отсутствуют заголовки Продажи <год>" }
        val combinedIndex = headers.optional("СЕЗОННОСТЬ")
        val yearColumn = headers.required("Год")
        val meanColumn = headers.required("Среднемес.")
        val yearTotalColumn = headers.required("Итого за год")
        val subsequentRows = sheet.rows.filter { it.number > monthlyHeaderRow.number }
        val monthRows = subsequentRows.filter { row ->
            !isTotal(row.text("B")) &&
                (row.hasContent("B") || yearGroups.any { row.hasContent(it.sales) || row.hasContent(it.coefficient) || row.hasContent(it.share) })
        }.map { row ->
            val month = russianMonth(row.text("B"))
            if (month == null) book.issue(sheetName, row.number, "B", "Не удалось определить месяц; значения строки сохранены", row.text("B"))
            SystemeSeasonalMonthRow(row.number, month, row.calculatedText("B"), yearGroups.map { it.read(row) }, combinedIndex?.let { row.calculated(it) })
        }
        val totals = subsequentRows.filter { isTotal(it.text("B")) }.map { row ->
            SystemeSeasonalTotal(row.number, yearGroups.map { it.read(row) }, combinedIndex?.let { row.calculated(it) })
        }
        val annualSummaries = subsequentRows.filter { row ->
            !normalized(row.text(yearColumn)).startsWith("поправка") &&
                (row.hasContent(yearColumn) || row.hasContent(meanColumn) || row.hasContent(yearTotalColumn))
        }.map { row ->
            if (row.text(yearColumn) == null) book.issue(sheetName, row.number, yearColumn, "Не указан год годовых знаменателей")
            SystemeSeasonalAnnualSummary(row.number, row.calculatedInteger(yearColumn), row.calculated(meanColumn), row.calculated(yearTotalColumn))
        }
        val corrections = subsequentRows.flatMap { row ->
            row.cells.keys.sortedBy(::columnNumber).mapNotNull { column ->
                val label = row.text(column) ?: return@mapNotNull null
                if (!normalized(label).startsWith("поправка")) return@mapNotNull null
                val years = Regex("(\\d{4})\\s*/\\s*(\\d{4})").find(label)
                val period = Regex("\\(([^)]+)\\)").find(label)?.groupValues?.get(1)
                SystemeSeasonalityCorrection(
                    row.number, label, years?.groupValues?.get(1)?.toInt(), years?.groupValues?.get(2)?.toInt(), period,
                    row.calculated(columnLetters(columnNumber(column) + 1)),
                )
            }
        }
        return SystemeSeasonalityReport(sheetName, monthRows, annualSales, annualSummaries, totals, corrections)
    }

    private data class HeaderColumn(val column: String, val label: String)

    private data class SeasonalYearColumns(val year: Int, val sales: String, val coefficient: String, val share: String) {
        fun read(row: ExcelRow) = SystemeSeasonalYearMetrics(year, row.calculated(sales), row.calculated(coefficient), row.calculated(share))
    }

    private fun seasonalYearColumns(headers: List<HeaderColumn>): List<SeasonalYearColumns> {
        val salesRegex = Regex("Продажи\\s+(\\d{4})", RegexOption.IGNORE_CASE)
        val sales = headers.mapNotNull { header -> salesRegex.matchEntire(header.label.trim())?.let { header to it.groupValues[1].toInt() } }
        return sales.mapIndexed { index, (salesHeader, year) ->
            val first = columnNumber(salesHeader.column)
            val last = sales.getOrNull(index + 1)?.first?.column?.let(::columnNumber) ?: Int.MAX_VALUE
            val group = headers.filter { columnNumber(it.column) in (first + 1) until last }
            val coefficient = group.firstOrNull { normalized(it.label).startsWith("коэф. сезонности") }
                ?: throw IllegalArgumentException("Отсутствует коэффициент сезонности для $year")
            val share = group.firstOrNull { normalized(it.label).startsWith("доля в году") }
                ?: throw IllegalArgumentException("Отсутствует доля в году для $year")
            SeasonalYearColumns(year, salesHeader.column, coefficient.column, share.column)
        }
    }

    private fun productRows(book: ExcelWorkbook, sheet: ExcelSheet, sheetName: String, start: Int, codeColumn: String, totalLabelColumn: String): List<ExcelRow> =
        sheet.rows.filter { row ->
            if (row.number < start || isTotal(row.text(totalLabelColumn))) return@filter false
            if (row.text(codeColumn) != null) return@filter true
            val hasContent = row.cells.values.any { !it.raw.isNullOrBlank() || it.formula != null || it.error != null }
            if (hasContent) book.issue(sheetName, row.number, codeColumn, "В строке отсутствует код товара; остальные поля сохранены")
            hasContent
        }

    private fun requireMonths(sheet: ExcelSheet, row: Int): List<MonthColumn> = monthColumns(sheet, row).also {
        require(it.isNotEmpty()) { "Строка заголовков $row не содержит месячных колонок" }
    }

    private fun headerColumns(row: ExcelRow): List<HeaderColumn> = row.cells.keys.sortedBy(::columnNumber).mapNotNull { column ->
        row.text(column)?.let { HeaderColumn(column, it) }
    }

    private fun List<HeaderColumn>.required(label: String): String = optional(label)
        ?: throw IllegalArgumentException("Отсутствует обязательная колонка $label")

    private fun List<HeaderColumn>.optional(label: String): String? {
        val matches = filter { normalized(it.label) == normalized(label) }
        require(matches.size <= 1) { "Неоднозначная колонка $label" }
        return matches.singleOrNull()?.column
    }

    private fun List<HeaderColumn>.singleMatching(regex: Regex, label: String): HeaderColumn =
        filter { regex.matches(it.label.trim()) }.also { require(it.size == 1) { "Ожидается одна колонка $label" } }.single()

    private fun List<HeaderColumn>.yearColumns(regex: Regex): Map<Int, String> = buildMap {
        for (header in this@yearColumns) {
            val match = regex.matchEntire(header.label.trim()) ?: continue
            val year = match.groupValues[1].toInt()
            require(year !in this) { "Повторный годовой заголовок ${header.label}" }
            put(year, header.column)
        }
    }

    private fun isTotal(value: String?): Boolean = normalized(value) == "итого"

    private fun normalized(value: String?): String = normalizeHeader(value)

    private fun russianMonth(value: String?): Month? = when (normalized(value).removeSuffix(".")) {
        "янв", "январь" -> Month.JANUARY
        "фев", "февр", "февраль" -> Month.FEBRUARY
        "мар", "март" -> Month.MARCH
        "апр", "апрель" -> Month.APRIL
        "май" -> Month.MAY
        "июн", "июнь" -> Month.JUNE
        "июл", "июль" -> Month.JULY
        "авг", "август" -> Month.AUGUST
        "сен", "сент", "сентябрь" -> Month.SEPTEMBER
        "окт", "октябрь" -> Month.OCTOBER
        "ноя", "нояб", "ноябрь" -> Month.NOVEMBER
        "дек", "декабрь" -> Month.DECEMBER
        else -> null
    }

    private fun columnNumber(column: String): Int = column.fold(0) { result, c -> result * 26 + (c - 'A' + 1) }

    private fun columnLetters(number: Int): String {
        var remainder = number
        var result = ""
        while (remainder > 0) {
            remainder--
            result = ('A' + remainder % 26) + result
            remainder /= 26
        }
        return result
    }
}

private fun ExcelRow.calculated(column: String): SystemeCalculatedDecimal =
    SystemeCalculatedDecimal("$column$number", decimal(column), cells[column]?.formula, cells[column]?.error)

private fun ExcelRow.calculatedText(column: String): SystemeCalculatedText =
    SystemeCalculatedText("$column$number", text(column), cells[column]?.formula, cells[column]?.error)

private fun ExcelRow.calculatedInteger(column: String): SystemeCalculatedInteger =
    SystemeCalculatedInteger("$column$number", integer(column), cells[column]?.formula, cells[column]?.error)

private fun ExcelRow.hasContent(column: String): Boolean = cells[column]?.let {
    !it.raw.isNullOrBlank() || it.formula != null || it.error != null
} == true
