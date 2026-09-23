package kz.hackalem.backend

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

@WebMvcTest(OrderPlanningController::class)
@Import(OrderPlanningService::class, IekOrderSource::class, SystemeOrderSource::class, DemandForecastEngine::class)
class OrderPlanningControllerTests {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var store: ExcelFileStore

    @Test
    fun `GET returns explainable recommendations using the shared typed snapshot`() {
        `when`(store.currentUpload).thenReturn(ExcelUpload(null, sampleSysteme(), emptyList()))
        mvc.perform(get("/api/orders").param("leadDays", "10").param("reviewDays", "20").param("historyMonths", "6"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].supplier_id").value("systeme"))
            .andExpect(jsonPath("$.items[0].erp_code").value("000001_"))
            .andExpect(jsonPath("$.items[0].order_qty").value(300))
            .andExpect(jsonPath("$.items[0].forecast_qty").value(300))
            .andExpect(jsonPath("$.items[0].explanation").isNotEmpty)
            .andExpect(jsonPath("$.items[0].stock_basis").value("AVAILABLE_STOCK"))
            .andExpect(jsonPath("$.parameters.horizonDays").value(30))
            .andExpect(jsonPath("$.suppliers[0].dataThrough").value("2026-07-15"))
        verify(store).currentUpload
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `calculation requires explicit planning horizon and valid parameters`() {
        mvc.perform(get("/api/orders")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/orders").param("leadDays", "-1").param("reviewDays", "30")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/orders").param("leadDays", "10").param("reviewDays", "0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/orders").param("leadDays", "10").param("reviewDays", "30").param("supplierId", "unknown"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `missing upload or unuploaded supplier have clear statuses`() {
        mvc.perform(get("/api/orders").param("leadDays", "10").param("reviewDays", "20"))
            .andExpect(status().isConflict)
        `when`(store.currentUpload).thenReturn(ExcelUpload(null, sampleSysteme(), emptyList()))
        mvc.perform(get("/api/orders").param("leadDays", "10").param("reviewDays", "20").param("supplierId", "iek"))
            .andExpect(status().isNotFound)
    }

    private fun sampleSysteme(): SystemeData {
        val reader = ExcelReader()
        val parser = SystemeExcelParser()
        val moq = parser.parseMoq(reader.read(ExcelFixtures.file("systemeMoq")))
        val dynamics = parser.parseSalesDynamics(reader.read(ExcelFixtures.file("systemeSalesDynamics")))
        val stocks = parser.parseMonthlyStocks(reader.read(ExcelFixtures.file("systemeMonthlyStocks")))
        val sales = parser.parseMonthlySales(reader.read(ExcelFixtures.file("systemeMonthlySales")))
        val incoming = parser.parseIncomingShipments(reader.read(ExcelFixtures.file("systemeIncomingShipments")))
        val seasonality = parser.parseSeasonality(reader.read(ExcelFixtures.file("systemeSeasonality")))
        val months = (1..6).map { YearMonth.of(2026, it) }
        val code = "000001_"
        return SystemeData(
            moq.copy(rows = listOf(moq.rows.single().copy(productCode = code, orderMultiple = BigDecimal.TEN))),
            dynamics.copy(rows = listOf(dynamics.rows.single().copy(productCode = code, unit = "шт", documentDateTime = LocalDateTime.of(2026, 7, 15, 12, 0)))),
            stocks.copy(rows = listOf(stocks.rows.single().copy(productCode = code, unit = "шт", monthlyStocks = months.map { MonthlyValue(it, BigDecimal("100")) }))),
            sales.copy(rows = listOf(sales.rows.single().copy(productCode = code, monthlySales = months.map { MonthlyValue(it, (it.lengthOfMonth() * 10).toBigDecimal()) }))),
            incoming.copy(rows = listOf(incoming.rows.single().copy(productCode = code, availableStock = BigDecimal.ZERO,
                growthChange = SystemeCalculatedDecimal("AR3", BigDecimal.ZERO, null, null),
                inTransit = listOf(SystemeInTransitQuantity("СЭ в пути", null, BigDecimal.ZERO))))),
            seasonality.copy(rows = seasonality.rows.map { it.copy(combinedSeasonalityIndex = SystemeCalculatedDecimal("L${it.sourceRow}", BigDecimal.ONE, null, null)) }),
        )
    }
}
