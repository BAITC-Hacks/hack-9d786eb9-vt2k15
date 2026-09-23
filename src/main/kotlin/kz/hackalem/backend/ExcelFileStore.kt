package kz.hackalem.backend

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class ExcelUpload(
    val iek: IekData?,
    val systeme: SystemeData?,
    val issues: List<ExcelIssue>,
) {
    init {
        require(iek != null || systeme != null) { "Нужны данные хотя бы одного поставщика." }
    }
}

@Component
class ExcelFileStore {
    val currentUpload: ExcelUpload?
        get() = uploads[CURRENT_UPLOAD_KEY]

    fun replace(upload: ExcelUpload) {
        // One atomic update replaces the entire snapshot, including the selected suppliers.
        uploads[CURRENT_UPLOAD_KEY] = upload
    }

    private companion object {
        const val CURRENT_UPLOAD_KEY = "current"
        val uploads = ConcurrentHashMap<String, ExcelUpload>()
    }
}
