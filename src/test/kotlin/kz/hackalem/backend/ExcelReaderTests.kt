package kz.hackalem.backend

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.*

class ExcelReaderTests {
    @Test
    fun `reads shared rich and inline strings cached decimals errors and formulas without trusting dimension`() {
        val book = ExcelReader().read(MockMultipartFile("report", "source.xlsx", null, ExcelFixtures.zip(linkedMapOf(
            "xl/workbook.xml" to """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Data" state="hidden" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="/xl/worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>0000123</t></si><si><r><t>Part </t></r><r><t>name</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><dimension ref="H9"/><sheetData><row r="2">
                <c r="A2" t="s"><v>0</v></c><c r="B2" t="s"><v>1</v></c>
                <c r="C2"><v>-1.250</v></c><c r="D2"><v>0</v></c><c r="E2" t="e"/>
                <c r="F2" t="e"><f>NA()</f><v>#N/A</v></c>
                <c r="G2"><f>1/2</f><v>0.5</v></c><c r="H2"><f>1+1</f></c>
                <c r="I2" t="inlineStr"><is><t>22.09.2027 16:15:38</t></is></c>
                <c r="J2" t="inlineStr"><is><t>not a number</t></is></c>
                </row></sheetData></worksheet>""",
        ))))
        val row = book.sheet("Data").row(2)
        assertEquals("hidden", book.sheet("Data").state)
        assertEquals("0000123", row.text("A"))
        assertEquals("Part name", row.text("B"))
        assertEquals(BigDecimal("-1.250"), row.decimal("C"))
        assertEquals(BigDecimal.ZERO, row.decimal("D"))
        assertNull(row.decimal("E"))
        assertNull(row.decimal("F"))
        assertEquals(BigDecimal("0.5"), row.decimal("G"))
        assertEquals("1/2", row.cells["G"]?.formula)
        assertEquals(LocalDateTime.of(2027, 9, 22, 16, 15, 38), row.dateTime("I"))
        assertNull(row.decimal("J"))
        assertEquals(setOf("F2", "H2", "J2"), book.issues.map { it.cell }.toSet())
        assertEquals("#N/A", book.issues.first { it.cell == "F2" }.rawValue)
    }

    @Test
    fun `reads periods from headers including future years`() {
        assertEquals(YearMonth.of(2029, 12), parseExcelMonth("Декабрь 2029 г."))
        assertEquals(YearMonth.of(2030, 1), parseExcelMonth("Янв. 2030"))
        assertNull(parseExcelMonth("Итого"))
    }
}
