package kz.hackalem.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.*

@SpringBootTest
@AutoConfigureMockMvc
class ExcelUploadTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: ExcelFileStore

    @Test
    fun `accepts twelve different schemas and stores typed values including hidden sheets`() {
        accept(ExcelFixtures.files())
        val saved = requireNotNull(store.currentUpload)
        assertEquals(1, saved.iek.moq.rows.size)
        assertNotNull(saved.iek.moq.rows.single().minimumShipmentQuantity)
        assertNotNull(saved.iek.salesDynamics.rows.single().occurredAt)
        assertEquals(33, saved.iek.monthlySales.rows.single().sales.size)
        assertEquals(33, saved.iek.monthlyStocks.rows.single().openingStocks.size)
        assertEquals(6, saved.iek.incomingShipments.shipments.size)
        assertEquals(12, saved.iek.seasonality.normalizedSeasonality.size)
        assertEquals(1, saved.systeme.moq.rows.size)
        assertNotNull(saved.systeme.salesDynamics.rows.single().documentDateTime)
        assertEquals(33, saved.systeme.monthlyStocks.rows.single().monthlyStocks.size)
        assertEquals(12, saved.systeme.monthlySales.hiddenSeasonality.rows.size)
        assertEquals(12, saved.systeme.incomingShipments.hiddenSeasonality.rows.size)
        assertEquals(1, saved.systeme.seasonality.corrections.size)
        assertNotNull(saved.systeme.incomingShipments.rows.single().reportedAverageMonthlySales.formula)
    }

    @ParameterizedTest
    @ValueSource(strings = ["iekMoq", "iekSeasonality", "systemeMoq", "systemeSeasonality"])
    fun `requires each named part`(missing: String) = reject(ExcelFixtures.files().filterNot { it.name == missing })

    @Test
    fun `rejects duplicate or unexpected parts`() {
        val files = ExcelFixtures.files()
        reject(files + files.first())
        reject(files + MockMultipartFile("extra", "extra.xlsx", null, byteArrayOf(1)))
    }

    @Test
    fun `rejects empty wrong extension corrupt workbook and wrong report without changing stored data`() {
        val files = ExcelFixtures.files()
        accept(files)
        for (bad in listOf(
            MockMultipartFile("systemeSeasonality", "empty.xlsx", null, byteArrayOf()),
            MockMultipartFile("systemeSeasonality", "report.csv", null, byteArrayOf(1)),
            MockMultipartFile("systemeSeasonality", "corrupt.xlsx", null, byteArrayOf(1, 2)),
            MockMultipartFile("systemeSeasonality", "wrong.xlsx", null, files.first().bytes),
        )) reject(files.dropLast(1) + bad)
    }

    @Test
    fun `new valid upload replaces the entire snapshot`() {
        val files = ExcelFixtures.files()
        accept(files)
        val previous = store.currentUpload
        accept(files.map { MockMultipartFile(it.name, "new-${it.originalFilename}", null, it.bytes) })
        assertNotSame(previous, store.currentUpload)
        assertEquals("new-iekMoq.xlsx", store.currentUpload?.iek?.moq?.fileName)
        assertEquals("new-systemeSeasonality.xlsx", store.currentUpload?.systeme?.seasonality?.fileName)
    }

    private fun accept(files: List<MockMultipartFile>) {
        mvc.perform(multipart("/api/excel").apply { files.forEach { file(it) } })
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalFiles").value(12))
            .andExpect(jsonPath("$.recordCounts.iekMoq").value(1))
            .andExpect(jsonPath("$.recordCounts.systemeIncomingShipments").value(1))
            .andExpect(jsonPath("$.recordCounts.iekSeasonality").value(12))
            .andExpect(jsonPath("$.issues").isArray)
    }

    private fun reject(files: List<MockMultipartFile>) {
        val previous = store.currentUpload
        mvc.perform(multipart("/api/excel").apply { files.forEach { file(it) } }).andExpect(status().isBadRequest)
        assertSame(previous, store.currentUpload)
    }
}
