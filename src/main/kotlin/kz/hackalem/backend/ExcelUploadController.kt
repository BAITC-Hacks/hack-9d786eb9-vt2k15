package kz.hackalem.backend

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.server.ResponseStatusException

data class UploadResponse(
    val iekFiles: Int,
    val systemeFiles: Int,
    val totalFiles: Int,
    val recordCounts: Map<String, Int>,
    val issues: List<ExcelIssue>,
)

@RestController
class ExcelUploadController(
    private val store: ExcelFileStore,
    private val reader: ExcelReader,
    private val iekParser: IekExcelParser,
    private val systemeParser: SystemeExcelParser,
) {
    @PostMapping("/api/excel", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("iekMoq", required = false) iekMoq: MultipartFile?,
        @RequestPart("iekSalesDynamics", required = false) iekSalesDynamics: MultipartFile?,
        @RequestPart("iekMonthlyStocks", required = false) iekMonthlyStocks: MultipartFile?,
        @RequestPart("iekMonthlySales", required = false) iekMonthlySales: MultipartFile?,
        @RequestPart("iekIncomingShipments", required = false) iekIncomingShipments: MultipartFile?,
        @RequestPart("iekSeasonality", required = false) iekSeasonality: MultipartFile?,
        @RequestPart("systemeMoq", required = false) systemeMoq: MultipartFile?,
        @RequestPart("systemeSalesDynamics", required = false) systemeSalesDynamics: MultipartFile?,
        @RequestPart("systemeMonthlyStocks", required = false) systemeMonthlyStocks: MultipartFile?,
        @RequestPart("systemeMonthlySales", required = false) systemeMonthlySales: MultipartFile?,
        @RequestPart("systemeIncomingShipments", required = false) systemeIncomingShipments: MultipartFile?,
        @RequestPart("systemeSeasonality", required = false) systemeSeasonality: MultipartFile?,
        request: MultipartHttpServletRequest,
    ): UploadResponse {
        val files = request.multiFileMap
        if (files.isEmpty()) {
            badRequest("Загрузите все 6 файлов хотя бы одного поставщика: IEK или Systeme Electric.")
        }
        val unknownFields = files.keys - (IEK_FIELDS + SYSTEME_FIELDS)
        if (unknownFields.isNotEmpty()) {
            badRequest("Неизвестные файловые поля: ${unknownFields.joinToString()}.")
        }
        if (files.values.any { it.size != 1 }) {
            badRequest("Каждое файловое поле должно содержать ровно один файл.")
        }

        fun supplierIncluded(supplier: String, fields: Set<String>): Boolean {
            if (fields.none { it in files }) return false
            val missing = fields - files.keys
            if (missing.isNotEmpty()) {
                badRequest("Для $supplier нужны все 6 файлов. Отсутствуют поля: ${missing.joinToString()}.")
            }
            return true
        }
        val hasIek = supplierIncluded("IEK", IEK_FIELDS)
        val hasSysteme = supplierIncluded("Systeme Electric", SYSTEME_FIELDS)

        files.values.flatten().forEach { file ->
            if (file.isEmpty) {
                badRequest("Файл ${file.originalFilename.orEmpty()} пустой.")
            }
            if (file.originalFilename?.endsWith(".xlsx", ignoreCase = true) != true) {
                badRequest("Принимаются только файлы с расширением .xlsx.")
            }
        }

        val issues = mutableListOf<ExcelIssue>()
        fun <T> parse(file: MultipartFile?, parser: (ExcelWorkbook) -> T): T {
            val sourceFile = file ?: badRequest("Отсутствует обязательный файл выбранного поставщика.")
            try {
                val book = reader.read(sourceFile)
                val data = parser(book)
                issues.addAll(book.issues)
                return data
            } catch (error: ExcelFormatException) {
                badRequest(error.message ?: "Некорректный формат Excel.")
            } catch (error: IllegalArgumentException) {
                badRequest("${sourceFile.originalFilename}: ${error.message}")
            }
        }

        // Publish only when all reports of every included supplier have been parsed successfully.
        val upload = ExcelUpload(
            iek = if (hasIek) IekData(
                moq = parse(iekMoq, iekParser::parseMoq),
                salesDynamics = parse(iekSalesDynamics, iekParser::parseSalesDynamics),
                monthlyStocks = parse(iekMonthlyStocks, iekParser::parseMonthlyStocks),
                monthlySales = parse(iekMonthlySales, iekParser::parseMonthlySales),
                incomingShipments = parse(iekIncomingShipments, iekParser::parseIncomingShipments),
                seasonality = parse(iekSeasonality, iekParser::parseSeasonality),
            ) else null,
            systeme = if (hasSysteme) SystemeData(
                moq = parse(systemeMoq, systemeParser::parseMoq),
                salesDynamics = parse(systemeSalesDynamics, systemeParser::parseSalesDynamics),
                monthlyStocks = parse(systemeMonthlyStocks, systemeParser::parseMonthlyStocks),
                monthlySales = parse(systemeMonthlySales, systemeParser::parseMonthlySales),
                incomingShipments = parse(systemeIncomingShipments, systemeParser::parseIncomingShipments),
                seasonality = parse(systemeSeasonality, systemeParser::parseSeasonality),
            ) else null,
            issues = issues.toList(),
        )
        store.replace(upload)
        return UploadResponse(
            iekFiles = if (hasIek) 6 else 0,
            systemeFiles = if (hasSysteme) 6 else 0,
            totalFiles = files.size,
            recordCounts = buildMap {
                upload.iek?.let { iek ->
                    put("iekMoq", iek.moq.rows.size)
                    put("iekSalesDynamics", iek.salesDynamics.rows.size)
                    put("iekMonthlyStocks", iek.monthlyStocks.rows.size)
                    put("iekMonthlySales", iek.monthlySales.rows.size)
                    put("iekIncomingShipments", iek.incomingShipments.rows.size)
                    put("iekSeasonality", iek.seasonality.yearlySeasonality.size)
                }
                upload.systeme?.let { systeme ->
                    put("systemeMoq", systeme.moq.rows.size)
                    put("systemeSalesDynamics", systeme.salesDynamics.rows.size)
                    put("systemeMonthlyStocks", systeme.monthlyStocks.rows.size)
                    put("systemeMonthlySales", systeme.monthlySales.rows.size)
                    put("systemeIncomingShipments", systeme.incomingShipments.rows.size)
                    put("systemeSeasonality", systeme.seasonality.rows.size)
                }
            },
            issues = upload.issues,
        )
    }

    private fun badRequest(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private companion object {
        val IEK_FIELDS = setOf(
            "iekMoq", "iekSalesDynamics", "iekMonthlyStocks", "iekMonthlySales", "iekIncomingShipments", "iekSeasonality",
        )
        val SYSTEME_FIELDS = setOf(
            "systemeMoq", "systemeSalesDynamics", "systemeMonthlyStocks", "systemeMonthlySales", "systemeIncomingShipments", "systemeSeasonality",
        )
    }
}
