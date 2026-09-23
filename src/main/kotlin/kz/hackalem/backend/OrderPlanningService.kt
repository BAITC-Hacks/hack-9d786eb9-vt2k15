package kz.hackalem.backend

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

@Service
class OrderPlanningService(
    private val store: ExcelFileStore,
    private val iekSource: IekOrderSource,
    private val systemeSource: SystemeOrderSource,
    private val forecastEngine: DemandForecastEngine,
) {
    fun recommend(parameters: OrderPlanningParameters, supplierId: String? = null, category: String? = null): OrderPlanningResponse {
        validate(parameters)
        if (supplierId != null && supplierId !in setOf("iek", "systeme")) badRequest("supplierId: допустимы iek или systeme.")
        // Take the reference once: a concurrent upload cannot mix old and new supplier data.
        val snapshot = store.currentUpload
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Сначала загрузите Excel-файлы через POST /api/excel.")
        val sources = buildList {
            if (supplierId == null || supplierId == "iek") snapshot.iek?.let { add(iekSource.build(it)) }
            if (supplierId == null || supplierId == "systeme") snapshot.systeme?.let { add(systemeSource.build(it)) }
        }
        if (sources.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Данные поставщика $supplierId не загружены.")
        return calculate(sources, parameters, category)
    }

    fun calculate(sources: List<PlanningSource>, parameters: OrderPlanningParameters, category: String? = null): OrderPlanningResponse {
        validate(parameters)
        val items = mutableListOf<OrderRecommendation>()
        val skipped = mutableListOf<SkippedOrderProduct>()
        val review = mutableListOf<SkippedOrderProduct>()
        val summaries = mutableListOf<OrderSupplierSummary>()
        val warnings = mutableListOf(
            "Расчёт является рекомендацией; заказы поставщикам не отправляются.",
            "ID клиентов и точные периоды отсутствия товара не предоставлены: выбросы определяются по документам, stockout оценивается по месячным срезам.",
            "Категории используются для отбора и группировки; бизнес-правила числовых кодов категорий в исходниках не определены.",
        )
        sources.forEach { source ->
            warnings.addAll(source.warnings.map { "${source.supplierName}: $it" })
            val products = source.products.filter { category == null || it.category == category }
            val firstItem = items.size
            val firstSkipped = skipped.size
            var noOrderNeeded = 0
            products.forEach productLoop@{ product ->
                val reasons = product.problems.toMutableList()
                val asOf = source.dataThrough
                val stock = product.stock
                if (asOf == null) reasons.add("Нет даты среза динамики продаж; дата сервера не подменяет дату данных.")
                if (stock == null) reasons.add("Остаток неизвестен; пустое значение не считается нулём.")
                if (asOf != null && product.stockMonth != null && product.stockMonth > YearMonth.from(asOf)) {
                    reasons.add("Месяц остатка позднее даты динамики продаж; источники не согласованы по времени.")
                }
                if (product.incoming.any { it.quantity != null && it.quantity.signum() < 0 }) {
                    reasons.add("Отрицательное количество в пути требует проверки.")
                }
                if (reasons.isNotEmpty()) {
                    skipped.add(SkippedOrderProduct(source.supplierId, product.code, product.name, reasons.distinct()))
                    return@productLoop
                }
                val forecast = forecastEngine.forecast(product, source, parameters)
                if (forecast == null) {
                    skipped.add(SkippedOrderProduct(source.supplierId, product.code, product.name,
                        listOf("Нет достаточной известной истории за полные месяцы выбранного периода.")))
                    return@productLoop
                }
                val horizonEnd = requireNotNull(asOf).plusDays(parameters.horizonDays.toLong())
                val itemWarnings = (product.warnings + forecast.warnings).toMutableList()
                val incoming = product.incoming.mapNotNull { it.quantity }.fold(BigDecimal.ZERO, BigDecimal::add)
                val incomingUsed = product.incoming.filter { it.expectedBy == null || it.expectedBy <= horizonEnd }
                    .mapNotNull { it.quantity }.fold(BigDecimal.ZERO, BigDecimal::add)
                if (product.incoming.isEmpty() || product.incoming.any { it.quantity == null }) {
                    itemWarnings.add("Путь неполон: вычтены только известные количества; неизвестная часть не включена. Заказ предварительный.")
                }
                if (product.incoming.any { (it.quantity?.signum() ?: 0) > 0 && it.expectedBy == null }) {
                    itemWarnings.add("Для части пути нет полной даты прибытия: предполагается поступление в пределах горизонта.")
                }
                if (product.incoming.any { (it.quantity?.signum() ?: 0) > 0 && it.expectedBy != null && it.expectedBy < asOf }) {
                    itemWarnings.add("В пути есть партия с прошедшим сроком: учтена как ожидаемая по предоставленному отчёту, статус нужно проверить.")
                }
                if (incomingUsed < incoming) itemWarnings.add("Партии после $horizonEnd не уменьшают заказ на выбранный горизонт.")
                val shortage = (forecast.forecastQuantity - requireNotNull(stock) - incomingUsed).max(BigDecimal.ZERO)
                if (shortage.signum() == 0) {
                    noOrderNeeded++
                    if (itemWarnings.isNotEmpty()) {
                        review.add(SkippedOrderProduct(source.supplierId, product.code, product.name,
                            listOf("Заказ не предложен: прогноз ${forecast.forecastQuantity.toPlainString()}, остаток ${stock.toPlainString()}, " +
                                "учтённый путь ${incomingUsed.toPlainString()}. Результат требует проверки допущений.") + itemWarnings.distinct()))
                    }
                    return@productLoop
                }
                var order = shortage.setScale(0, RoundingMode.CEILING)
                product.minimumShipment?.let { minimum ->
                    if (minimum.signum() > 0) order = order.max(minimum)
                    else itemWarnings.add("Некорректный минимум отгрузки не применён.")
                }
                product.orderMultiple?.let { multiple ->
                    if (multiple.signum() > 0) order = order.divide(multiple, 0, RoundingMode.CEILING).multiply(multiple)
                    else itemWarnings.add("Некорректная кратность не применена.")
                }
                if (product.minimumShipment == null && product.orderMultiple == null) {
                    itemWarnings.add("Условия партии неизвестны: количество округлено до целой единицы учёта; условия закупки нужно проверить.")
                }
                val daily = forecast.forecastQuantity.divide(parameters.horizonDays.toBigDecimal(), 12, RoundingMode.HALF_UP)
                val urgency = when {
                    stock.signum() <= 0 || stock < daily.multiply(parameters.leadDays.toBigDecimal()) -> "high"
                    stock < forecast.forecastQuantity -> "medium"
                    else -> "low"
                }
                val stockDescription = if (product.stockBasis == "AVAILABLE_STOCK") "свободный остаток" else "остаток на начало ${product.stockMonth}"
                val explanation = "Прогноз на ${parameters.horizonDays} дней: ${forecast.forecastQuantity.toPlainString()} ${product.unit.orEmpty()}. " +
                    "Учтены сезонность, тренд ${forecast.trendFactor.toPlainString()}, рост источника ${forecast.sourceGrowthFactor.toPlainString()}, " +
                    "прогноз прироста ${parameters.forecastGrowthPercent.toPlainString()}%. " +
                    "Компенсация stockout в истории: ${forecast.stockoutAdjustment.toPlainString()}, " +
                    "исключены разовые продажи: ${forecast.excludedOutlierQuantity.toPlainString()}. " +
                    "$stockDescription: ${stock.toPlainString()}, путь в горизонте: ${incomingUsed.toPlainString()}. " +
                    "Потребность max(0, ${forecast.forecastQuantity.toPlainString()} − ${stock.toPlainString()} − ${incomingUsed.toPlainString()}) " +
                    "= ${shortage.toPlainString()}; с округлением и условиями партии к заказу ${order.toPlainString()}."
                items.add(OrderRecommendation(
                    product.code, source.supplierId, source.supplierName, product.article, product.name, product.unit,
                    product.category, order, forecast.forecastQuantity, stock, product.stockBasis, product.stockMonth,
                    incoming, incomingUsed, product.minimumShipment, product.orderMultiple, urgency,
                    product.stockBasis != "AVAILABLE_STOCK" || itemWarnings.isNotEmpty(), forecast, explanation, itemWarnings.distinct(),
                ))
            }
            summaries.add(OrderSupplierSummary(source.supplierId, source.supplierName, source.dataThrough,
                products.size, items.size - firstItem, skipped.size - firstSkipped, noOrderNeeded))
        }
        val priority = mapOf("high" to 0, "medium" to 1, "low" to 2)
        return OrderPlanningResponse(parameters,
            items.sortedWith(compareBy<OrderRecommendation> { it.supplierId }.thenBy { priority[it.urgency] }.thenBy { it.erpCode }),
            skipped, review, summaries, warnings.distinct())
    }

    private fun validate(parameters: OrderPlanningParameters) {
        if (parameters.leadDays !in 0..365) badRequest("leadDays должен быть от 0 до 365.")
        if (parameters.reviewDays !in 1..365) badRequest("reviewDays должен быть от 1 до 365.")
        if (parameters.historyMonths !in 6..36) badRequest("historyMonths должен быть от 6 до 36.")
        if (parameters.forecastGrowthPercent < BigDecimal("-100") || parameters.forecastGrowthPercent > BigDecimal("300")) {
            badRequest("forecastGrowthPercent должен быть от -100 до 300.")
        }
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
