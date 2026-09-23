package kz.hackalem.backend

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.time.YearMonth
import kotlin.test.*

/** Optional full-data regression: original business exports are deliberately not committed. */
@SpringBootTest
@AutoConfigureMockMvc
class SourceWorkbooksTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: ExcelFileStore

    @Test
    fun `imports all original records through REST and preserves known source anomalies`() {
        val paths = listOf(
            "IEK/MOQ  ИЭК.xlsx",
            "IEK/Динамика продаж_2025-2026.xlsx",
            "IEK/Ежемесячные остатки продукции за последние 2 года  ИЭК.xlsx",
            "IEK/Ежемесячные продажи в количественном выражении за последние 2 года.xlsx",
            "IEK/Путь ИЭК 22.09.2026.xlsx",
            "IEK/Сезонность ИЭК.xlsx",
            "Systeme electric/MOQ SystemElectric.xlsx",
            "Systeme electric/Динамика продаж_Syseme Electric_2025-2026.xlsx",
            "Systeme electric/Ежемесячные остатки SystemElectric 2024-2026.xlsx",
            "Systeme electric/Ежемесячные продажи в кол-м выражении SystemElectric 2024-2026.xlsx",
            "Systeme electric/Товар в пути_SystemElectric на 22.09.2026.xlsx",
            "Systeme electric/Сезонность SystemElectric 2024-2026.xlsx",
        ).map { Path.of("task spec", it) }
        assumeTrue(paths.all { Files.isRegularFile(it) }, "Original Excel files are available only in the local task spec folder")
        val request = multipart("/api/excel")
        ExcelFixtures.parts.zip(paths).forEach { (part, path) ->
            Files.newInputStream(path).use { request.file(MockMultipartFile(part, path.fileName.toString(), null, it)) }
        }
        val response = mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
        Files.createDirectories(Path.of("build", "verification"))
        Files.writeString(Path.of("build", "verification", "source-upload.json"), response)
        val saved = requireNotNull(store.currentUpload)
        val iek = requireNotNull(saved.iek)
        val systeme = requireNotNull(saved.systeme)
        assertEquals(listOf(1938, 171603, 2853, 2463, 2641, 12), iek.let {
            listOf(it.moq.rows.size, it.salesDynamics.rows.size, it.monthlyStocks.rows.size,
                it.monthlySales.rows.size, it.incomingShipments.rows.size, it.seasonality.yearlySeasonality.size)
        })
        assertEquals(listOf(554, 77312, 701, 554, 497, 12), systeme.let {
            listOf(it.moq.rows.size, it.salesDynamics.rows.size, it.monthlyStocks.rows.size,
                it.monthlySales.rows.size, it.incomingShipments.rows.size, it.seasonality.rows.size)
        })
        assertEquals(115, iek.salesDynamics.rows.count { (it.quantity?.signum() ?: 0) < 0 })
        assertEquals(18, iek.salesDynamics.rows.count { it.quantity == null })
        assertEquals(302, systeme.salesDynamics.rows.count { (it.quantity?.signum() ?: 0) < 0 })
        assertEquals(13, systeme.salesDynamics.rows.count { it.quantity == null })
        assertTrue(iek.salesDynamics.rows.all { it.occurredAt != null })
        assertTrue(systeme.salesDynamics.rows.all { it.documentDateTime != null })
        assertEquals(18, iek.incomingShipments.rows.count { it.productCode == null })
        assertEquals(6, iek.incomingShipments.shipments.size)
        assertTrue(iek.incomingShipments.shipments.all { it.orderDate != null && it.expectedBy != null })
        assertEquals(YearMonth.of(2026, 9), iek.monthlyStocks.rows.first().openingStocks.last().month)
        assertEquals(12, iek.seasonality.normalizedSeasonality.size)
        assertEquals(3, iek.seasonality.annualBases.size)
        assertEquals(12, systeme.monthlySales.hiddenSeasonality.rows.size)
        assertEquals(12, systeme.incomingShipments.hiddenSeasonality.rows.size)
        assertTrue(systeme.incomingShipments.rows.first { it.sourceRow == 485 }.reportedAverageMonthlySales.formula.orEmpty().endsWith("/5"))
        assertEquals(15, saved.issues.count { it.rawValue == "#N/A" })
        assertEquals(15, saved.issues.size)

        val orders = mvc.perform(get("/api/orders").param("leadDays", "60").param("reviewDays", "30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isNotEmpty)
            .andExpect(jsonPath("$.items[?(@.supplier_id == 'systeme')]").isNotEmpty)
            .andExpect(jsonPath("$.suppliers.length()").value(2))
            .andExpect(jsonPath("$.parameters.horizonDays").value(90))
            .andExpect(jsonPath("$.skipped").isArray)
            .andExpect(jsonPath("$.review").isArray)
            .andReturn().response.contentAsString
        Files.writeString(Path.of("build", "verification", "source-orders.json"), orders)
        assertSame(saved, store.currentUpload, "Calculating an order must not replace or consume the upload")
    }
}
