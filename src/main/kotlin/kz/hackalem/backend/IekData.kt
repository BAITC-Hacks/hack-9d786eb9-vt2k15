package kz.hackalem.backend

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Parsed values from the six native IEK worksheets. External-link catalog caches are not imported. */
data class IekData(
    val moq: IekMoqData,
    val salesDynamics: IekSalesDynamicsData,
    val monthlyStocks: IekMonthlyStocksData,
    val monthlySales: IekMonthlySalesData,
    val incomingShipments: IekIncomingShipmentsData,
    val seasonality: IekSeasonalityData,
)

data class IekMoqData(val fileName: String, val rows: List<IekMoqRow>)

data class IekMoqRow(
    val sourceRow: Int,
    val ordinal: Int?,
    val productCode: String?,
    val supplierArticle: String?,
    val productName: String?,
    /** The external price list's minimum shipment quantity, not its separate order multiple. */
    val minimumShipmentQuantity: BigDecimal?,
)

data class IekSalesDynamicsData(
    val fileName: String,
    val rows: List<IekSalesDynamicsRow>,
    val totals: List<IekSalesDynamicsTotal>,
)

data class IekSalesDynamicsRow(
    val sourceRow: Int,
    val occurredAt: LocalDateTime?,
    val documentNumber: String?,
    val document: String?,
    val productCode: String?,
    val productName: String?,
    val unit: String?,
    val warehouse: String?,
    val quantity: BigDecimal?,
)

data class IekSalesDynamicsTotal(val sourceRow: Int, val quantity: BigDecimal?)

data class IekMonthlyStocksData(
    val fileName: String,
    val rows: List<IekMonthlyStockRow>,
    val totals: List<IekMonthlyStockTotal>,
)

data class IekMonthlyStockRow(
    val sourceRow: Int,
    val productName: String?,
    val unit: String?,
    val productCode: String?,
    val openingStocks: List<MonthlyValue>,
    /** The source's 'Итого': opening balance of the entire period, not a sum over months. */
    val periodOpeningStock: BigDecimal?,
)

data class IekMonthlyStockTotal(
    val sourceRow: Int,
    val openingStocks: List<MonthlyValue>,
    val periodOpeningStock: BigDecimal?,
)

data class IekMonthlySalesData(
    val fileName: String,
    val rows: List<IekMonthlySalesRow>,
    val totals: List<IekMonthlySalesTotal>,
)

data class IekMonthlySalesRow(
    val sourceRow: Int,
    val productName: String?,
    val productCode: String?,
    val sales: List<MonthlyValue>,
    val periodTotal: BigDecimal?,
)

data class IekMonthlySalesTotal(
    val sourceRow: Int,
    val sales: List<MonthlyValue>,
    val periodTotal: BigDecimal?,
)

data class IekIncomingShipmentsData(
    val fileName: String,
    val shipments: List<IekShipmentColumn>,
    val rows: List<IekIncomingShipmentRow>,
    val totals: List<IekIncomingShipmentTotal>,
)

data class IekShipmentColumn(
    val sourceColumn: String,
    val sourceHeader: String?,
    val documentPrefix: String?,
    val orderNumber: String?,
    val orderDate: LocalDate?,
    /** A latest expected arrival date ('поступление до'), not an actual receipt date. */
    val expectedBy: LocalDate?,
)

data class IekIncomingShipmentRow(
    val sourceRow: Int,
    val productCode: String?,
    val supplierArticle: String?,
    val productName: String?,
    val quantities: List<IekShipmentQuantity>,
)

data class IekShipmentQuantity(val sourceColumn: String, val quantity: BigDecimal?)

data class IekIncomingShipmentTotal(val sourceRow: Int, val quantities: List<IekShipmentQuantity>)

data class IekSeasonalityData(
    val fileName: String,
    val annualSales: List<IekAnnualSalesRow>,
    val yearlySeasonality: List<IekYearlySeasonalityRow>,
    val yearlySeasonalityTotals: List<IekYearlySeasonalityTotal>,
    val annualBases: List<IekAnnualSeasonalityBaseRow>,
    val normalizedSeasonality: List<IekNormalizedSeasonalityRow>,
    val normalizationTotals: List<IekSeasonalityNormalizationTotal>,
)

data class IekCalendarMonthValue(val month: Int, val value: BigDecimal?)

data class IekAnnualSalesRow(
    val sourceRow: Int,
    val year: Int?,
    /** The source does not identify the sales unit or currency. */
    val monthlySales: List<IekCalendarMonthValue>,
    val total: BigDecimal?,
)

data class IekYearlySeasonalityRow(
    val sourceRow: Int,
    val monthLabel: String?,
    val month: Int?,
    val years: List<IekSeasonalityYearValues>,
    val normalizedCoefficient: BigDecimal?,
)

data class IekSeasonalityYearValues(
    val year: Int,
    val sales: BigDecimal?,
    val coefficient: BigDecimal?,
    val annualShare: BigDecimal?,
)

data class IekYearlySeasonalityTotal(
    val sourceRow: Int,
    val years: List<IekSeasonalityYearTotal>,
    val normalizedCoefficientAverage: BigDecimal?,
)

data class IekSeasonalityYearTotal(
    val year: Int,
    val salesTotal: BigDecimal?,
    val coefficientAverage: BigDecimal?,
    val annualShareTotal: BigDecimal?,
)

data class IekAnnualSeasonalityBaseRow(
    val sourceRow: Int,
    val year: Int?,
    /** Averages filled source months; an incomplete year is not divided by twelve. */
    val monthlyAverage: BigDecimal?,
    val annualTotal: BigDecimal?,
)

data class IekSeasonalityCoefficient(val year: Int, val value: BigDecimal?)

data class IekNormalizedSeasonalityRow(
    val sourceRow: Int,
    val monthLabel: String?,
    val month: Int?,
    val coefficients: List<IekSeasonalityCoefficient>,
    val averageCoefficient: BigDecimal?,
    val normalizedCoefficient: BigDecimal?,
    val annualShare: BigDecimal?,
    /** Preserves the hidden block's forecast explanation, including future-month assumptions. */
    val note: String?,
)

data class IekSeasonalityNormalizationTotal(
    val sourceRow: Int,
    val normalizedCoefficientAverage: BigDecimal?,
    val annualShareTotal: BigDecimal?,
)
