package kz.hackalem.backend

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Month

/** Parsed values from all six Systeme workbooks, rather than opaque uploaded bytes. */
data class SystemeData(
    val moq: SystemeMoqData,
    val salesDynamics: SystemeSalesDynamicsData,
    val monthlyStocks: SystemeMonthlyStocksData,
    val monthlySales: SystemeMonthlySalesData,
    val incomingShipments: SystemeIncomingShipmentsData,
    val seasonality: SystemeSeasonalityData,
)

/** Value is Excel's saved result. Formulas are retained for provenance and never evaluated here. */
data class SystemeCalculatedDecimal(
    val sourceCell: String,
    val value: BigDecimal?,
    val formula: String?,
    val error: String?,
)

data class SystemeCalculatedText(
    val sourceCell: String,
    val value: String?,
    val formula: String?,
    val error: String?,
)

data class SystemeCalculatedInteger(
    val sourceCell: String,
    val value: Int?,
    val formula: String?,
    val error: String?,
)

data class SystemeMoqData(val fileName: String, val rows: List<SystemeMoqRow>)

data class SystemeMoqRow(
    val sourceRow: Int,
    val reportNumber: Int?,
    val productName: String?,
    val productCode: String?,
    val supplierArticle: String?,
    /** The source says Кратность; it does not contain a separate minimum order quantity. */
    val orderMultiple: BigDecimal?,
)

data class SystemeSalesDynamicsData(
    val fileName: String,
    val rows: List<SystemeSalesDynamicsRow>,
    val totals: List<SystemeSalesDynamicsTotal>,
)

data class SystemeSalesDynamicsRow(
    val sourceRow: Int,
    val documentDateTime: LocalDateTime?,
    val documentNumber: String?,
    val documentDescription: String?,
    val productCode: String?,
    val productName: String?,
    val unit: String?,
    val warehouse: String?,
    val quantity: BigDecimal?,
)

data class SystemeSalesDynamicsTotal(val sourceRow: Int, val quantity: BigDecimal?)

data class SystemeMonthlyStocksData(val fileName: String, val rows: List<SystemeMonthlyStockRow>)

data class SystemeMonthlyStockRow(
    val sourceRow: Int,
    val reportNumber: Int?,
    val productName: String?,
    val productCode: String?,
    val unit: String?,
    /** The source does not specify whether these are closing or average monthly stocks. */
    val monthlyStocks: List<MonthlyValue>,
)

data class SystemeMonthlySalesData(
    val fileName: String,
    val rows: List<SystemeMonthlySalesRow>,
    val totals: List<SystemeMonthlySalesTotal>,
    val hiddenSeasonality: SystemeSeasonalityReport,
)

data class SystemeMonthlySalesRow(
    val sourceRow: Int,
    val productName: String?,
    val productCode: String?,
    val supplierArticle: String?,
    val orderMultiple: BigDecimal?,
    val monthlySales: List<MonthlyValue>,
    val totalQuantity: BigDecimal?,
)

data class SystemeMonthlySalesTotal(
    val sourceRow: Int,
    val monthlySales: List<MonthlyValue>,
    val totalQuantity: BigDecimal?,
)

data class SystemeIncomingShipmentsData(
    val fileName: String,
    val rows: List<SystemeIncomingShipmentRow>,
    val hiddenSeasonality: SystemeSeasonalityReport,
)

data class SystemeCategory(val year: Int, val code: String?)

data class SystemeAnnualProductSales(
    val year: Int,
    val totalSales: SystemeCalculatedDecimal?,
    val averageMonthlySales: SystemeCalculatedDecimal?,
)

data class SystemeInTransitQuantity(
    val sourceHeader: String,
    /** Date text such as 24.09; no year or confirmed ETA is fabricated. */
    val dateLabel: String?,
    val quantity: BigDecimal?,
)

data class SystemeIncomingShipmentRow(
    val sourceRow: Int,
    val reportNumber: Int?,
    val supplierArticle: String?,
    val productCode: String?,
    val productName: String?,
    /** Codes 1/2/3/5/7 are not described as ABC classes in the source. */
    val category: SystemeCategory,
    /** Unexplained source label СС реал. Currency and price/cost semantics are unknown. */
    val ssReal: BigDecimal?,
    val monthlySales: List<MonthlyValue>,
    val annualSales: List<SystemeAnnualProductSales>,
    /** Source AP label says 12 months, although its current formula sums 13 columns. */
    val reportedRollingSales: SystemeCalculatedDecimal,
    /** Preserves the per-product /5 exception as well as the usual /12 formula. */
    val reportedAverageMonthlySales: SystemeCalculatedDecimal,
    val growthChange: SystemeCalculatedDecimal,
    val seasonalityChange: SystemeCalculatedDecimal,
    /** Витрина is a quantity, including 2 and 17, not a boolean. */
    val displayQuantity: BigDecimal?,
    val tzStock: BigDecimal?,
    val ryskulovaDistributionStock: BigDecimal?,
    val retailWarehouseStock: BigDecimal?,
    val stock: BigDecimal?,
    val reservedStock: BigDecimal?,
    val availableStock: BigDecimal?,
    /** Запас is coverage in months and includes the proposed order and transit. */
    val stockCoverageMonths: SystemeCalculatedDecimal,
    val proposedOrderQuantity: BigDecimal?,
    val inTransit: List<SystemeInTransitQuantity>,
    /** BH has a header but no values. The source does not give a weight unit. */
    val weight: BigDecimal?,
)

/** Annual upper table: the unit of these aggregated sales measures is not specified. */
data class SystemeAnnualSalesRow(
    val sourceRow: Int,
    val year: Int?,
    val monthlySales: List<SystemeCalendarMonthValue>,
    val total: SystemeCalculatedDecimal,
)

data class SystemeCalendarMonthValue(val month: Month, val value: BigDecimal?)

data class SystemeSeasonalYearMetrics(
    val year: Int,
    val sales: SystemeCalculatedDecimal,
    val seasonalityIndex: SystemeCalculatedDecimal,
    val shareOfYear: SystemeCalculatedDecimal,
)

data class SystemeSeasonalMonthRow(
    val sourceRow: Int,
    val month: Month?,
    val sourceMonthLabel: SystemeCalculatedText,
    val years: List<SystemeSeasonalYearMetrics>,
    /** Exists only in the standalone seasonality workbook, not the two hidden reports. */
    val combinedSeasonalityIndex: SystemeCalculatedDecimal?,
)

data class SystemeSeasonalAnnualSummary(
    val sourceRow: Int,
    val year: SystemeCalculatedInteger,
    val averageMonthlySales: SystemeCalculatedDecimal,
    val totalSales: SystemeCalculatedDecimal,
)

data class SystemeSeasonalityCorrection(
    val sourceRow: Int,
    val sourceLabel: String,
    val numeratorYear: Int?,
    val denominatorYear: Int?,
    val periodLabel: String?,
    val factor: SystemeCalculatedDecimal,
)

data class SystemeSeasonalTotal(
    val sourceRow: Int,
    val years: List<SystemeSeasonalYearMetrics>,
    val combinedSeasonalityIndex: SystemeCalculatedDecimal?,
)

data class SystemeSeasonalityReport(
    val sheetName: String,
    val rows: List<SystemeSeasonalMonthRow>,
    val annualSales: List<SystemeAnnualSalesRow>,
    val annualSummaries: List<SystemeSeasonalAnnualSummary>,
    val totals: List<SystemeSeasonalTotal>,
    val corrections: List<SystemeSeasonalityCorrection>,
)

data class SystemeSeasonalityData(
    val fileName: String,
    val rows: List<SystemeSeasonalMonthRow>,
    val annualSales: List<SystemeAnnualSalesRow>,
    val annualSummaries: List<SystemeSeasonalAnnualSummary>,
    val totals: List<SystemeSeasonalTotal>,
    val corrections: List<SystemeSeasonalityCorrection>,
)
