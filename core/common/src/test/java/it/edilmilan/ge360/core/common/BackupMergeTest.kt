package it.edilmilan.ge360.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupMergeTest {
    private data class Row(val id: String, val value: String)

    @Test
    fun mergeKeepsExistingAndIncomingWinsOnSameKey() {
        val result = BackupMerge.byKey(
            current = listOf(Row("a", "old"), Row("b", "keep")),
            incoming = listOf(Row("a", "new"), Row("c", "add")),
            replace = false,
            key = Row::id
        )

        assertEquals(listOf(Row("a", "new"), Row("b", "keep"), Row("c", "add")), result)
    }

    @Test
    fun replaceDropsRowsMissingFromBackup() {
        val result = BackupMerge.byKey(
            current = listOf(Row("a", "old"), Row("b", "old")),
            incoming = listOf(Row("c", "restored")),
            replace = true,
            key = Row::id
        )

        assertEquals(listOf(Row("c", "restored")), result)
    }

    @Test
    fun inputListsAreNotMutated() {
        val current = mutableListOf(Row("a", "old"))
        val incoming = mutableListOf(Row("a", "new"))

        BackupMerge.byKey(current, incoming, replace = false, key = Row::id)

        assertEquals(listOf(Row("a", "old")), current)
        assertEquals(listOf(Row("a", "new")), incoming)
    }
}
