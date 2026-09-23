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
        val iek = requireNotNull(saved.iek)
        val systeme = requireNotNull(saved.systeme)
        assertEquals(1, iek.moq.rows.size)
        assertNotNull(iek.moq.rows.single().minimumShipmentQuantity)
        assertNotNull(iek.salesDynamics.rows.single().occurredAt)
        assertEquals(33, iek.monthlySales.rows.single().sales.size)
        assertEquals(33, iek.monthlyStocks.rows.single().openingStocks.size)
        assertEquals(6, iek.incomingShipments.shipments.size)
        assertEquals(12, iek.seasonality.normalizedSeasonality.size)
        assertEquals(1, systeme.moq.rows.size)
        assertNotNull(systeme.salesDynamics.rows.single().documentDateTime)
        assertEquals(33, systeme.monthlyStocks.rows.single().monthlyStocks.size)
        assertEquals(12, systeme.monthlySales.hiddenSeasonality.rows.size)
        assertEquals(12, systeme.incomingShipments.hiddenSeasonality.rows.size)
        assertEquals(1, systeme.seasonality.corrections.size)
        assertNotNull(systeme.incomingShipments.rows.single().reportedAverageMonthlySales.formula)
    }

    @Test
    fun `accepts only six IEK reports and removes the previous Systeme group`() {
        val files = ExcelFixtures.files()
        accept(files)
        accept(files.filter { it.name.startsWith("iek") })

        val saved = requireNotNull(store.currentUpload)
        val iek = requireNotNull(saved.iek)
        assertEquals(1, iek.moq.rows.size)
        assertEquals(33, iek.monthlySales.rows.single().sales.size)
        assertEquals(12, iek.seasonality.normalizedSeasonality.size)
        assertNull(saved.systeme)
    }

    @Test
    fun `accepts only six Systeme reports and removes the previous IEK group`() {
        val files = ExcelFixtures.files()
        accept(files)
        accept(files.filter { it.name.startsWith("systeme") })

        val saved = requireNotNull(store.currentUpload)
        val systeme = requireNotNull(saved.systeme)
        assertEquals(1, systeme.moq.rows.size)
        assertEquals(12, systeme.monthlySales.hiddenSeasonality.rows.size)
        assertEquals(1, systeme.seasonality.corrections.size)
        assertNull(saved.iek)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "iekMoq", "iekSalesDynamics", "iekMonthlyStocks", "iekMonthlySales", "iekIncomingShipments", "iekSeasonality",
        "systemeMoq", "systemeSalesDynamics", "systemeMonthlyStocks", "systemeMonthlySales", "systemeIncomingShipments", "systemeSeasonality",
    ])
    fun `requires every role within a submitted supplier group`(missing: String) {
        val files = ExcelFixtures.files()
        val supplier = if (missing.startsWith("iek")) "iek" else "systeme"
        val supplierLabel = if (supplier == "iek") "IEK" else "Systeme Electric"
        val expectedDetail = "Для $supplierLabel нужны все 6 файлов. Отсутствуют поля: $missing."
        // A partial group is invalid both alone and alongside a complete other group.
        reject(files.filter { it.name.startsWith(supplier) && it.name != missing }, expectedDetail)
        reject(files.filterNot { it.name == missing }, expectedDetail)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 5, 7, 11])
    fun `rejects zero files and incomplete supplier combinations`(count: Int) =
        reject(ExcelFixtures.files().take(count))

    @Test
    fun `six files split three per supplier are not a complete group`() {
        val files = ExcelFixtures.files()
        reject(files.filter { it.name.startsWith("iek") }.take(3) + files.filter { it.name.startsWith("systeme") }.take(3))
    }

    @Test
    fun `rejects duplicate or unexpected parts`() {
        val files = ExcelFixtures.files()
        reject(files + files.first())
        reject(files + MockMultipartFile("extra", "extra.xlsx", null, byteArrayOf(1)))
        val iekFiles = files.filter { it.name.startsWith("iek") }
        reject(iekFiles + iekFiles.first())
        reject(iekFiles + iekFiles)
        reject(iekFiles + MockMultipartFile("extra", "extra.xlsx", null, byteArrayOf(1)))
        reject(iekFiles + (1..6).map { MockMultipartFile("extra$it", "extra$it.xlsx", null, byteArrayOf(1)) })
    }

    @Test
    fun `snapshot requires at least one supplier group`() {
        assertFailsWith<IllegalArgumentException> { ExcelUpload(null, null, emptyList()) }
    }

    @Test
    fun `a failed single supplier import preserves the entire previous snapshot`() {
        val files = ExcelFixtures.files()
        accept(files.filter { it.name.startsWith("iek") })
        val systemeFiles = files.filter { it.name.startsWith("systeme") }
        reject(systemeFiles.dropLast(1) + MockMultipartFile("systemeSeasonality", "corrupt.xlsx", null, byteArrayOf(1, 2)))
        assertNotNull(store.currentUpload?.iek)
        assertNull(store.currentUpload?.systeme)
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

    @Test
    fun `switching suppliers replaces the snapshot instead of merging old groups`() {
        val files = ExcelFixtures.files()
        accept(files.filter { it.name.startsWith("iek") })
        val previous = requireNotNull(store.currentUpload)
        accept(files.filter { it.name.startsWith("systeme") }
            .map { MockMultipartFile(it.name, "new-${it.originalFilename}", null, it.bytes) })

        val replacement = requireNotNull(store.currentUpload)
        assertNotSame(previous, replacement)
        assertNull(replacement.iek)
        assertEquals("new-systemeMoq.xlsx", requireNotNull(replacement.systeme).moq.fileName)

        accept(files)
        assertNotSame(replacement, store.currentUpload)
        assertEquals("iekMoq.xlsx", store.currentUpload?.iek?.moq?.fileName)
        assertEquals("systemeMoq.xlsx", store.currentUpload?.systeme?.moq?.fileName)
    }

    @Test
    fun `store instances share the latest upload and reading does not consume it`() {
        val otherStore = ExcelFileStore()
        val files = ExcelFixtures.files()
        accept(files)
        val firstUpload = requireNotNull(store.currentUpload)
        assertSame(firstUpload, otherStore.currentUpload)
        assertSame(firstUpload, otherStore.currentUpload)

        accept(files.map { MockMultipartFile(it.name, "updated-${it.originalFilename}", null, it.bytes) })
        assertNotSame(firstUpload, otherStore.currentUpload)
        assertSame(store.currentUpload, otherStore.currentUpload)
        assertEquals("updated-iekMoq.xlsx", otherStore.currentUpload?.iek?.moq?.fileName)
        assertEquals("updated-systemeMoq.xlsx", otherStore.currentUpload?.systeme?.moq?.fileName)
    }

    private fun accept(files: List<MockMultipartFile>) {
        val iekCount = files.count { it.name.startsWith("iek") }
        val systemeCount = files.count { it.name.startsWith("systeme") }
        val result = mvc.perform(multipart("/api/excel").apply { files.forEach { file(it) } })
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.iekFiles").value(iekCount))
            .andExpect(jsonPath("$.systemeFiles").value(systemeCount))
            .andExpect(jsonPath("$.totalFiles").value(files.size))
            .andExpect(jsonPath("$.recordCounts.length()").value(files.size))
            .andExpect(jsonPath("$.issues").isArray)
        val submittedParts = files.map { it.name }.toSet()
        ExcelFixtures.parts.forEach { part ->
            if (part in submittedParts) {
                val expectedRows = if (part.endsWith("Seasonality")) 12 else 1
                result.andExpect(jsonPath("$.recordCounts.$part").value(expectedRows))
            } else {
                result.andExpect(jsonPath("$.recordCounts.$part").doesNotExist())
            }
        }
    }

    private fun reject(files: List<MockMultipartFile>, expectedDetail: String? = null) {
        val previous = store.currentUpload
        val result = mvc.perform(multipart("/api/excel").apply { files.forEach { file(it) } })
            .andExpect(status().isBadRequest)
        if (expectedDetail != null) result.andExpect(jsonPath("$.detail").value(expectedDetail))
        assertSame(previous, store.currentUpload)
    }
}
