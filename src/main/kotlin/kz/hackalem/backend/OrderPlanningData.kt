package kz.hackalem.backend

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/** A calculation reads one immutable upload snapshot; these inputs are never stored back into it. */
data class PlanningSource(
    val supplierId: String,
    val supplierName: String,
    val dataThrough: LocalDate?,
    val products: List<PlanningProduct>,
    val seasonality: Map<Month, BigDecimal>,
    val warnings: List<String> = emptyList(),
)

data class PlanningProduct(
    val code: String,
    val article: String?,
    val name: String?,
    val unit: String?,
    val category: String? = null,
    val monthlySales: List<MonthlyValue> = emptyList(),
    val stockHistory: List<MonthlyValue> = emptyList(),
    val sales: List<PlanningSale> = emptyList(),
    val stock: BigDecimal? = null,
    val stockBasis: String,
    val stockMonth: YearMonth? = null,
    val incoming: List<PlanningIncoming> = emptyList(),
    val minimumShipment: BigDecimal? = null,
    val orderMultiple: BigDecimal? = null,
    val sourceGrowthChange: BigDecimal? = null,
    val problems: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class PlanningSale(
    val date: LocalDate?,
    val documentNumber: String?,
    val document: String?,
    val quantity: BigDecimal?,
)

data class PlanningIncoming(val quantity: BigDecimal?, val expectedBy: LocalDate?, val reference: String?)

data class OrderPlanningParameters(
    val leadDays: Int,
    val reviewDays: Int,
    val historyMonths: Int,
    val forecastGrowthPercent: BigDecimal,
) {
    val horizonDays: Int get() = leadDays + reviewDays
}

data class DemandForecast(
    val historyFrom: YearMonth,
    val historyThrough: YearMonth,
    val baseDailyDemand: BigDecimal,
    val trendFactor: BigDecimal,
    val sourceGrowthFactor: BigDecimal,
    val seasonalFactor: BigDecimal,
    val forecastQuantity: BigDecimal,
    val stockoutAdjustment: BigDecimal,
    val excludedOutlierQuantity: BigDecimal,
    val warnings: List<String>,
)

data class OrderRecommendation(
    @get:JsonProperty("erp_code") val erpCode: String,
    @get:JsonProperty("supplier_id") val supplierId: String,
    @get:JsonProperty("supplier_name") val supplierName: String,
    @get:JsonProperty("supplier_article") val supplierArticle: String?,
    val name: String?,
    val unit: String?,
    val category: String?,
    @get:JsonProperty("order_qty") val orderQty: BigDecimal,
    @get:JsonProperty("forecast_qty") val forecastQty: BigDecimal,
    @get:JsonProperty("stock_qty") val stockQty: BigDecimal,
    @get:JsonProperty("stock_basis") val stockBasis: String,
    @get:JsonProperty("stock_month") val stockMonth: YearMonth?,
    @get:JsonProperty("in_transit") val inTransit: BigDecimal,
    @get:JsonProperty("in_transit_used") val inTransitUsed: BigDecimal,
    @get:JsonProperty("minimum_shipment_qty") val minimumShipmentQty: BigDecimal?,
    @get:JsonProperty("order_multiple") val orderMultiple: BigDecimal?,
    val urgency: String,
    val estimated: Boolean,
    val calculation: DemandForecast,
    val explanation: String,
    val warnings: List<String>,
)

data class SkippedOrderProduct(
    val supplierId: String,
    val productCode: String,
    val productName: String?,
    val reasons: List<String>,
)

data class OrderSupplierSummary(
    val supplierId: String,
    val supplierName: String,
    val dataThrough: LocalDate?,
    val productsConsidered: Int,
    val recommendations: Int,
    val skipped: Int,
    val noOrderNeeded: Int,
)

data class OrderPlanningResponse(
    val parameters: OrderPlanningParameters,
    val items: List<OrderRecommendation>,
    val skipped: List<SkippedOrderProduct>,
    val review: List<SkippedOrderProduct>,
    val suppliers: List<OrderSupplierSummary>,
    val warnings: List<String>,
)
