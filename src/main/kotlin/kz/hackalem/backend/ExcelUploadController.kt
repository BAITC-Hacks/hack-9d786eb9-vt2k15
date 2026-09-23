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
        @RequestPart("iekMoq") iekMoq: MultipartFile,
        @RequestPart("iekSalesDynamics") iekSalesDynamics: MultipartFile,
        @RequestPart("iekMonthlyStocks") iekMonthlyStocks: MultipartFile,
        @RequestPart("iekMonthlySales") iekMonthlySales: MultipartFile,
        @RequestPart("iekIncomingShipments") iekIncomingShipments: MultipartFile,
        @RequestPart("iekSeasonality") iekSeasonality: MultipartFile,
        @RequestPart("systemeMoq") systemeMoq: MultipartFile,
        @RequestPart("systemeSalesDynamics") systemeSalesDynamics: MultipartFile,
        @RequestPart("systemeMonthlyStocks") systemeMonthlyStocks: MultipartFile,
        @RequestPart("systemeMonthlySales") systemeMonthlySales: MultipartFile,
        @RequestPart("systemeIncomingShipments") systemeIncomingShipments: MultipartFile,
        @RequestPart("systemeSeasonality") systemeSeasonality: MultipartFile,
        request: MultipartHttpServletRequest,
    ): UploadResponse {
        val files = request.multiFileMap
        if (files.size != 12 || files.values.any { it.size != 1 }) {
            badRequest("Нужно ровно по одному файлу в каждом из 12 обязательных полей.")
        }

        files.values.flatten().forEach { file ->
            if (file.isEmpty) {
                badRequest("Файл ${file.originalFilename.orEmpty()} пустой.")
            }
            if (file.originalFilename?.endsWith(".xlsx", ignoreCase = true) != true) {
                badRequest("Принимаются только файлы с расширением .xlsx.")
            }
        }

        val issues = mutableListOf<ExcelIssue>()
        fun <T> parse(file: MultipartFile, parser: (ExcelWorkbook) -> T): T {
            try {
                val book = reader.read(file)
                val data = parser(book)
                issues.addAll(book.issues)
                return data
            } catch (error: ExcelFormatException) {
                badRequest(error.message ?: "Некорректный формат Excel.")
            } catch (error: IllegalArgumentException) {
                badRequest("${file.originalFilename}: ${error.message}")
            }
        }

        // Publish only when all twelve reports have been parsed successfully.
        val upload = ExcelUpload(
            iek = IekData(
                moq = parse(iekMoq, iekParser::parseMoq),
                salesDynamics = parse(iekSalesDynamics, iekParser::parseSalesDynamics),
                monthlyStocks = parse(iekMonthlyStocks, iekParser::parseMonthlyStocks),
                monthlySales = parse(iekMonthlySales, iekParser::parseMonthlySales),
                incomingShipments = parse(iekIncomingShipments, iekParser::parseIncomingShipments),
                seasonality = parse(iekSeasonality, iekParser::parseSeasonality),
            ),
            systeme = SystemeData(
                moq = parse(systemeMoq, systemeParser::parseMoq),
                salesDynamics = parse(systemeSalesDynamics, systemeParser::parseSalesDynamics),
                monthlyStocks = parse(systemeMonthlyStocks, systemeParser::parseMonthlyStocks),
                monthlySales = parse(systemeMonthlySales, systemeParser::parseMonthlySales),
                incomingShipments = parse(systemeIncomingShipments, systemeParser::parseIncomingShipments),
                seasonality = parse(systemeSeasonality, systemeParser::parseSeasonality),
            ),
            issues = issues.toList(),
        )
        store.replace(upload)
        return UploadResponse(
            iekFiles = 6, systemeFiles = 6, totalFiles = 12,
            recordCounts = linkedMapOf(
                "iekMoq" to upload.iek.moq.rows.size,
                "iekSalesDynamics" to upload.iek.salesDynamics.rows.size,
                "iekMonthlyStocks" to upload.iek.monthlyStocks.rows.size,
                "iekMonthlySales" to upload.iek.monthlySales.rows.size,
                "iekIncomingShipments" to upload.iek.incomingShipments.rows.size,
                "iekSeasonality" to upload.iek.seasonality.yearlySeasonality.size,
                "systemeMoq" to upload.systeme.moq.rows.size,
                "systemeSalesDynamics" to upload.systeme.salesDynamics.rows.size,
                "systemeMonthlyStocks" to upload.systeme.monthlyStocks.rows.size,
                "systemeMonthlySales" to upload.systeme.monthlySales.rows.size,
                "systemeIncomingShipments" to upload.systeme.incomingShipments.rows.size,
                "systemeSeasonality" to upload.systeme.seasonality.rows.size,
            ),
            issues = upload.issues,
        )
    }

    private fun badRequest(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
