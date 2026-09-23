package kz.hackalem.backend

import org.springframework.mock.web.MockMultipartFile
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Compact source-format fixtures: real headers, one product, complete seasonal blocks. */
object ExcelFixtures {
    val parts = listOf(
        "iekMoq", "iekSalesDynamics", "iekMonthlyStocks", "iekMonthlySales", "iekIncomingShipments", "iekSeasonality",
        "systemeMoq", "systemeSalesDynamics", "systemeMonthlyStocks", "systemeMonthlySales", "systemeIncomingShipments", "systemeSeasonality",
    )

    fun files() = parts.map(::file)

    fun file(part: String): MockMultipartFile {
        val resource = requireNotNull(javaClass.getResourceAsStream("/excel/$part.xml"))
        val doc = resource.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val sheets = doc.getElementsByTagName("sheet")
        val workbook = StringBuilder("""<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        val relationships = StringBuilder("<Relationships>")
        val entries = linkedMapOf<String, String>()
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as Element
            workbook.append("""<sheet name="${sheet.getAttribute("name")}" state="${sheet.getAttribute("state")}" r:id="rId${i + 1}"/>""")
            relationships.append("""<Relationship Id="rId${i + 1}" Target="worksheets/sheet${i + 1}.xml"/>""")
            val rows = StringWriter()
            val children = sheet.getElementsByTagName("row")
            val transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty("omit-xml-declaration", "yes")
            for (r in 0 until children.length) transformer.transform(DOMSource(children.item(r)), StreamResult(rows))
            entries["xl/worksheets/sheet${i + 1}.xml"] = "<worksheet><sheetData>$rows</sheetData></worksheet>"
        }
        entries["xl/workbook.xml"] = workbook.append("</sheets></workbook>").toString()
        entries["xl/_rels/workbook.xml.rels"] = relationships.append("</Relationships>").toString()
        return MockMultipartFile(part, "$part.xlsx", null, zip(entries))
    }

    fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, xml) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
