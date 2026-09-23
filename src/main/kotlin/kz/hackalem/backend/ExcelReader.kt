package kz.hackalem.backend

import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.io.FilterInputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

data class ExcelIssue(
    val fileName: String,
    val sheet: String,
    val cell: String,
    val message: String,
    val rawValue: String? = null,
)

class ExcelFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// These cells are transient parser input. The store keeps report DTOs, not this grid.
data class ExcelCell(val raw: String?, val formula: String? = null, val error: String? = null)
data class MonthColumn(val column: String, val month: YearMonth)
data class MonthlyValue(val month: YearMonth, val value: BigDecimal?)

class ExcelRow(
    val number: Int,
    val cells: Map<String, ExcelCell>,
    private val fileName: String,
    private val sheetName: String,
    private val issues: MutableList<ExcelIssue>,
) {
    fun text(column: String): String? = cells[column]?.takeIf { it.error == null }?.raw
        ?.takeUnless { it.isBlank() }

    fun decimal(column: String): BigDecimal? {
        val value = text(column) ?: return null
        return value.trim().toBigDecimalOrNull() ?: run {
            issue(column, "Ожидалось число.", value)
            null
        }
    }

    fun integer(column: String): Int? {
        val value = decimal(column) ?: return null
        return try {
            value.intValueExact()
        } catch (_: ArithmeticException) {
            issue(column, "Ожидалось целое число в диапазоне Int.", value.toPlainString())
            null
        }
    }

    fun dateTime(column: String): LocalDateTime? {
        val value = text(column)?.trim() ?: return null
        for (format in DATE_FORMATS) {
            try {
                return LocalDateTime.parse(value, format)
            } catch (_: java.time.format.DateTimeParseException) {
                // Try the other supported source representation.
            }
        }
        issue(column, "Не удалось прочитать дату и время.", value)
        return null
    }

    fun monthlyValues(columns: List<MonthColumn>): List<MonthlyValue> =
        columns.map { MonthlyValue(it.month, decimal(it.column)) }

    private fun issue(column: String, message: String, raw: String?) {
        issues.add(ExcelIssue(fileName, sheetName, "$column$number", message, raw))
    }

    private companion object {
        val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("d.M.uuuu H:m:s").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
    }
}

class ExcelSheet(
    val name: String,
    val rows: List<ExcelRow>,
    val state: String,
    private val fileName: String,
    private val issues: MutableList<ExcelIssue>,
) {
    private val byNumber = rows.associateBy { it.number }

    fun row(number: Int): ExcelRow = byNumber[number]
        ?: ExcelRow(number, emptyMap(), fileName, name, issues)

    fun requireHeaders(row: Int, expected: Map<String, String>) {
        expected.forEach { (column, label) ->
            val actual = this.row(row).text(column)
            if (normalizeHeader(actual) != normalizeHeader(label)) {
                throw ExcelFormatException("$fileName, $name!$column$row: ожидался заголовок «$label», получено «${actual.orEmpty()}».")
            }
        }
    }
}

class ExcelWorkbook(
    val fileName: String,
    val sheets: Map<String, ExcelSheet>,
    val issues: MutableList<ExcelIssue>,
) {
    fun sheet(name: String): ExcelSheet = sheets[name]
        ?: throw ExcelFormatException("$fileName: отсутствует лист «$name».")

    fun issue(sheet: String, row: Int, column: String, message: String, rawValue: String? = null) {
        issues.add(ExcelIssue(fileName, sheet, "$column$row", message, rawValue))
    }
}

fun normalizeHeader(text: String?): String = text.orEmpty().replace('\u00a0', ' ')
    .trim().replace(Regex("\\s+"), " ").lowercase()

fun parseExcelMonth(text: String?): YearMonth? {
    val match = Regex("^([а-яё]+)\\.?\\s+(\\d{4})(?:\\s*г\\.?)?$")
        .matchEntire(normalizeHeader(text)) ?: return null
    val month = when (match.groupValues[1].take(3)) {
        "янв" -> 1
        "фев" -> 2
        "мар" -> 3
        "апр" -> 4
        "май", "мая" -> 5
        "июн" -> 6
        "июл" -> 7
        "авг" -> 8
        "сен" -> 9
        "окт" -> 10
        "ноя" -> 11
        "дек" -> 12
        else -> return null
    }
    return YearMonth.of(match.groupValues[2].toInt(), month)
}

fun monthColumns(sheet: ExcelSheet, headerRow: Int): List<MonthColumn> =
    sheet.row(headerRow).cells.mapNotNull { (column, cell) ->
        parseExcelMonth(cell.raw)?.let { MonthColumn(column, it) }
    }

/** Reads the known XLSX format in two streaming passes, without building an XML DOM. */
@Component
class ExcelReader {
    fun read(file: MultipartFile): ExcelWorkbook {
        val fileName = file.originalFilename.orEmpty()
        try {
            val sharedStrings = mutableListOf<String>()
            val sheetDefinitions = mutableListOf<SheetDefinition>()
            val relationships = mutableMapOf<String, String>()
            entries(file) { path, input ->
                when (path) {
                    "xl/sharedStrings.xml" -> readSharedStrings(input, sharedStrings)
                    "xl/workbook.xml" -> xml(input) { reader ->
                        while (reader.hasNext()) {
                            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "sheet") {
                                val relationId = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "id" }
                                    ?.let(reader::getAttributeValue)
                                    ?: throw ExcelFormatException("$fileName: у листа нет relationship ID.")
                                sheetDefinitions.add(SheetDefinition(
                                    reader.getAttributeValue(null, "name"), relationId,
                                    reader.getAttributeValue(null, "state") ?: "visible",
                                ))
                            }
                        }
                    }
                    "xl/_rels/workbook.xml.rels" -> xml(input) { reader ->
                        while (reader.hasNext()) {
                            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "Relationship") {
                                if (reader.getAttributeValue(null, "TargetMode") != "External") {
                                    relationships[reader.getAttributeValue(null, "Id")] =
                                        reader.getAttributeValue(null, "Target")
                                }
                            }
                        }
                    }
                }
            }
            if (sheetDefinitions.isEmpty()) throw ExcelFormatException("$fileName: не найдена книга XLSX.")
            val sheetsByPath = sheetDefinitions.associateBy { definition ->
                val target = relationships[definition.relationId]
                    ?: throw ExcelFormatException("$fileName: отсутствует ссылка на лист ${definition.name}.")
                if (target.startsWith('/')) target.removePrefix("/")
                else java.net.URI("xl/").resolve(target).normalize().toString()
            }
            val issues = mutableListOf<ExcelIssue>()
            val sheets = linkedMapOf<String, ExcelSheet>()
            entries(file) { path, input ->
                val definition = sheetsByPath[path]
                if (definition != null) {
                    val rows = readRows(input, sharedStrings, fileName, definition.name, issues)
                    sheets[definition.name] = ExcelSheet(definition.name, rows, definition.state, fileName, issues)
                }
            }
            if (sheets.size != sheetDefinitions.size) throw ExcelFormatException("$fileName: отсутствуют данные листа.")
            return ExcelWorkbook(fileName, sheets, issues)
        } catch (error: ExcelFormatException) {
            throw error
        } catch (error: Exception) {
            throw ExcelFormatException("$fileName: не удалось прочитать XLSX (${error.message}).", error)
        }
    }

    private fun entries(file: MultipartFile, consume: (String, InputStream) -> Unit) {
        ZipInputStream(file.inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) consume(entry.name, zip)
                zip.closeEntry()
            }
        }
    }

    private fun readSharedStrings(input: InputStream, strings: MutableList<String>) = xml(input) { reader ->
        var current: StringBuilder? = null
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "si" -> current = StringBuilder()
                    "t" -> current?.append(reader.elementText)
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "si") {
                    strings.add(current.toString())
                    current = null
                }
            }
        }
    }

    private fun readRows(
        input: InputStream, shared: List<String>, fileName: String, sheet: String,
        issues: MutableList<ExcelIssue>,
    ): List<ExcelRow> = xml(input) { reader ->
        val rows = mutableListOf<ExcelRow>()
        var rowNumber = 0
        var cells = linkedMapOf<String, ExcelCell>()
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "row" -> {
                        rowNumber = reader.getAttributeValue(null, "r")?.toInt() ?: rowNumber + 1
                        cells = linkedMapOf()
                    }
                    "c" -> {
                        val address = reader.getAttributeValue(null, "r")
                            ?: throw ExcelFormatException("$fileName: ячейка без адреса.")
                        val type = reader.getAttributeValue(null, "t")
                        var value: String? = null
                        var formula: String? = null
                        val inline = StringBuilder()
                        while (reader.hasNext()) {
                            val event = reader.next()
                            if (event == XMLStreamConstants.END_ELEMENT && reader.localName == "c") break
                            if (event == XMLStreamConstants.START_ELEMENT) {
                                when (reader.localName) {
                                    "v" -> value = reader.elementText
                                    "f" -> formula = reader.elementText
                                    "t" -> inline.append(reader.elementText)
                                }
                            }
                        }
                        val raw = when (type) {
                            "s" -> value?.toIntOrNull()?.let { shared.getOrNull(it) }
                            "inlineStr" -> inline.toString()
                            else -> value
                        }
                        // 1C also emits t="e" for blank cells without a <v>; those are missing values.
                        val error = if (type == "e") raw?.takeUnless { it.isBlank() } else null
                        if (raw != null || formula != null || error != null) {
                            cells[address.takeWhile { it.isLetter() }] = ExcelCell(raw, formula, error)
                        }
                        if (error != null) issues.add(ExcelIssue(fileName, sheet, address, "Ошибка Excel: $error", raw))
                        else if (formula != null && raw == null) {
                            issues.add(ExcelIssue(fileName, sheet, address, "Нет сохранённого результата формулы."))
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "row" && cells.isNotEmpty()) {
                    rows.add(ExcelRow(rowNumber, cells, fileName, sheet, issues))
                }
            }
        }
        rows
    }

    private fun <T> xml(input: InputStream, block: (XMLStreamReader) -> T): T {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        // StAX may close its input at END_DOCUMENT; the ZIP must remain open for the next entry.
        val reader = factory.createXMLStreamReader(object : FilterInputStream(input) {
            override fun close() = Unit
        })
        return try { block(reader) } finally { reader.close() }
    }

    private data class SheetDefinition(val name: String, val relationId: String, val state: String)
}
