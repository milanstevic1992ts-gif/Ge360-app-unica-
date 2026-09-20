package it.edilmilan.ge360.core.common

/** Regola unica per gli import incrementali dei backup GE360. */
object BackupMerge {
    fun <T, K> byKey(
        current: List<T>,
        incoming: List<T>,
        replace: Boolean,
        key: (T) -> K
    ): List<T> {
        if (replace) return incoming.toList()
        val merged = LinkedHashMap<K, T>(current.size + incoming.size)
        current.forEach { merged[key(it)] = it }
        incoming.forEach { merged[key(it)] = it }
        return merged.values.toList()
    }
}
