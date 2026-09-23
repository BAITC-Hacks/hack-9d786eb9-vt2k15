package kz.hackalem.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
class OrderPlanningController(private val planning: OrderPlanningService) {
    @GetMapping("/api/orders")
    fun recommend(
        @RequestParam leadDays: Int,
        @RequestParam reviewDays: Int,
        @RequestParam(defaultValue = "12") historyMonths: Int,
        @RequestParam(defaultValue = "0") forecastGrowthPercent: BigDecimal,
        @RequestParam(required = false) supplierId: String?,
        @RequestParam(required = false) category: String?,
    ): OrderPlanningResponse = planning.recommend(
        OrderPlanningParameters(leadDays, reviewDays, historyMonths, forecastGrowthPercent), supplierId, category,
    )
}
