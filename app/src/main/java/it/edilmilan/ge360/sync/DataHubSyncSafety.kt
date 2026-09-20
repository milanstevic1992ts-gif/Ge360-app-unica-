package it.edilmilan.ge360.sync

/** Pure policy kept separate so a reinstall can never silently overwrite server state. */
internal object DataHubSyncSafety {
    fun canPush(hasKnownRevision: Boolean, knownRevision: Int, serverRevision: Int): Boolean = when {
        !hasKnownRevision -> serverRevision == 0
        else -> knownRevision == serverRevision
    }
}
