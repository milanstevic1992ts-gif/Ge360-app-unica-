package it.edilmilan.ge360.core.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name-scoped cleanup contract; intentionally NOT RUN until explicit QA.
 * No file access and no Android device dependencies.
 */
class BackupScratchV9Test {
    private val id = "00000000-1111-2222-3333-444444444444"

    @Test fun cleanupCoversOnlyKnownInterruptedPrivateStages() {
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-v8-build-$id.zip"))
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-restore-$id.ge360.zip"))
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-rollback-$id.ge360.zip"))
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-restore-source-$id.tmp"))
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-recovery-source-$id.tmp"))
        assertTrue(BackupScratchV9.isOwnedScratchName("ge360-recovery-check-$id.ge360.zip"))
        assertTrue(BackupScratchV9.isOwnedScratchName("restore_1762038000000.ge360.zip"))
    }

    @Test fun cleanupNeverSelectsCompletedBackupsOrOtherModules() {
        assertFalse(BackupScratchV9.isOwnedScratchName("clients.json"))
        assertFalse(BackupScratchV9.isOwnedScratchName("ge360-restore-evil.zip"))
        assertFalse(BackupScratchV9.isOwnedScratchName("../ge360-v8-build-$id.zip"))
        assertFalse(BackupScratchV9.isOwnedPartialArchiveName(
            "GE360_AUTO_20261101_120000_aaaabbbb.ge360.enc"))
        assertFalse(BackupScratchV9.isOwnedPartialArchiveName(
            "GE360_FULL_20261101_120000_aaaabbbb.ge360.zip"))
        assertTrue(BackupScratchV9.isOwnedPartialArchiveName(
            "GE360_FULL_20261101_120000_aaaabbbb.ge360.zip.partial"))
        assertTrue(BackupScratchV9.isOwnedPartialArchiveName(
            "GE360_AUTO_20261101_120000_aaaabbbb.ge360.enc.partial"))
        assertFalse(BackupScratchV9.isOwnedPartialArchiveName(
            "GE360_AUTO_20261101_120000_aaaabbbb.ge360.enc.partial/../../private"))
    }
}
