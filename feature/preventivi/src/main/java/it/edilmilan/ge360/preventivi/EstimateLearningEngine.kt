package it.edilmilan.ge360.preventivi

import java.util.Locale
import kotlin.math.round

data class WorkPriceStat(
    val key: String,
    val name: String,
    val unit: String,
    val uses: Int,
    val lastPrice: Double,
    val averagePrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val lastUsedAt: Long
)

object EstimateLearningEngine {
    fun stats(store: EstimateStore, includeDrafts: Boolean = false): List<WorkPriceStat> {
        val acceptedStatuses = setOf(
            EstimateStatuses.ACCEPTED,
            EstimateStatuses.IN_PROGRESS,
            EstimateStatuses.COMPLETED
        )
        data class Sample(val name: String, val unit: String, val price: Double, val at: Long)
        val samples = mutableMapOf<String, MutableList<Sample>>()
        store.list().forEach { estimate ->
            if (!includeDrafts && estimate.status !in acceptedStatuses) return@forEach
            estimate.items.forEach { item ->
                if (!item.included || item.optional || item.unitPrice <= 0 || item.category == "Prezzo ambiente") return@forEach
                val key = canonicalKey(item)
                samples.getOrPut(key) { mutableListOf() } += Sample(item.name, item.unit, item.unitPrice, estimate.updatedAt)
            }
        }
        return samples.map { (key, rows) ->
            val ordered = rows.sortedBy { it.at }
            val prices = rows.map { it.price }
            WorkPriceStat(
                key = key,
                name = ordered.last().name,
                unit = ordered.last().unit,
                uses = rows.size,
                lastPrice = ordered.last().price,
                averagePrice = round2Learning(prices.average()),
                minPrice = round2Learning(prices.minOrNull() ?: 0.0),
                maxPrice = round2Learning(prices.maxOrNull() ?: 0.0),
                lastUsedAt = ordered.last().at
            )
        }.sortedWith(compareByDescending<WorkPriceStat> { it.uses }.thenBy { it.name.lowercase(Locale.ITALY) })
    }

    fun statFor(store: EstimateStore, item: EstimateItem): WorkPriceStat? {
        val key = canonicalKey(item)
        return stats(store).firstOrNull { it.key == key }
            ?: stats(store, includeDrafts = true).firstOrNull { it.key == key }
    }

    fun priceFromMarkup(cost: Double, markupPercent: Double): Double =
        round2Learning(cost.coerceAtLeast(0.0) * (1.0 + markupPercent.coerceAtLeast(0.0) / 100.0))

    fun priceFromMargin(cost: Double, marginPercent: Double): Double {
        val margin = marginPercent.coerceIn(0.0, 99.0) / 100.0
        return if (cost <= 0) 0.0 else round2Learning(cost / (1.0 - margin))
    }

    fun achievedMargin(price: Double, cost: Double): Double =
        if (price <= 0) 0.0 else round2Learning((price - cost) * 100.0 / price)

    fun achievedMarkup(price: Double, cost: Double): Double =
        if (cost <= 0) 0.0 else round2Learning((price - cost) * 100.0 / cost)

    fun canonicalKey(item: EstimateItem): String {
        val code = item.priceListCode.trim()
        if (code.isNotBlank() && !code.startsWith(RoomManualWorkDialog.MANUAL_PREFIX)) {
            val normalizedCode = when {
                code.startsWith(RoomWorkCatalog.ITEM_PREFIX) -> code.substringAfterLast('/')
                else -> code
            }
            return "CODE:${normalizedCode.uppercase(Locale.ROOT)}"
        }
        return "NAME:${normalize(item.name)}|${item.unit.lowercase(Locale.ROOT)}"
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ITALY)
        .replace(Regex("[^a-zà-ÿ0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun round2Learning(value: Double) = round(value * 100.0) / 100.0
