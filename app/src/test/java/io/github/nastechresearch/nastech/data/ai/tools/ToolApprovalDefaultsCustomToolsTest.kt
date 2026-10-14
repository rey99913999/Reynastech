package io.github.nastechresearch.nastech.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalDefaultsCustomToolsTest {
    @Test
    fun customToolsAlwaysRequireApproval() {
        assertTrue(ToolApprovalDefaults.requiresApproval("ct_hello_world"))
    }

    @Test
    fun customToolsCannotBeAddedToAlwaysAllow() {
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("ct_hello_world"))
    }

    @Test
    fun ordinaryReadToolsKeepTheirExistingApprovalPolicy() {
        assertFalse(ToolApprovalDefaults.requiresApproval("get_time_info"))
        assertTrue(ToolApprovalDefaults.requiresApproval("eval_javascript"))
    }
}
