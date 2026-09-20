package it.edilmilan.ge360.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisProactiveActionPolicyTest {
    @Test
    fun prepareRequiresExplicitApprovalAndNoAutoExecute() {
        assertTrue(JarvisProactiveActionPolicy.canPrepare(true, true, false))
        assertFalse(JarvisProactiveActionPolicy.canPrepare(false, true, false))
        assertFalse(JarvisProactiveActionPolicy.canPrepare(true, false, false))
        assertFalse(JarvisProactiveActionPolicy.canPrepare(true, true, true))
    }

    @Test
    fun approveOnlyAcceptsPendingExplicitProposal() {
        assertTrue(JarvisProactiveActionPolicy.canApprove("proposed", true, false))
        assertFalse(JarvisProactiveActionPolicy.canApprove("executed", true, false))
        assertFalse(JarvisProactiveActionPolicy.canApprove("proposed", false, false))
        assertFalse(JarvisProactiveActionPolicy.canApprove("proposed", true, true))
    }
}
