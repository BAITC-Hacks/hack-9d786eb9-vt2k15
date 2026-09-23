package kz.hackalem.backend

import org.springframework.stereotype.Component

data class ExcelUpload(
    val iek: IekData,
    val systeme: SystemeData,
    val issues: List<ExcelIssue>,
)

@Component
class ExcelFileStore {
    @Volatile
    final var currentUpload: ExcelUpload? = null
        private set

    fun replace(upload: ExcelUpload) {
        currentUpload = upload
    }
}
