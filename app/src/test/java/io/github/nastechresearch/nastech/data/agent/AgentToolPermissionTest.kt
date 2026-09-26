package io.github.nastechresearch.nastech.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPermissionTest {

    @Test
    fun `plan-only agent receives no tools even when wildcard is configured`() {
        val agent = AgentDefinition(
            name = "Planner",
            role = AgentRole.PLANNER,
            autonomyLevel = AgentAutonomyLevel.PLAN_ONLY,
            allowedTools = listOf("*"),
        )
        assertTrue(resolveAgentTools(agent, listOf("read_window_tree", "send_sms", "shell")).isEmpty())
    }

    @Test
    fun `denied tools are removed from the explicit permission set`() {
        val agent = AgentDefinition(
            name = "Executor",
            role = AgentRole.EXECUTOR,
            autonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
            allowedTools = listOf("read_window_tree", "find_node", "shell"),
            deniedTools = listOf("shell"),
        )
        assertEquals(
            listOf("read_window_tree", "find_node"),
            resolveAgentTools(agent, listOf("read_window_tree", "find_node", "shell")),
        )
    }

    @Test
    fun `wildcard grants currently available tools before sensitive-action gate`() {
        val agent = AgentDefinition(
            name = "Reviewer",
            role = AgentRole.REVIEWER,
            autonomyLevel = AgentAutonomyLevel.SAFE_AUTO,
            allowedTools = listOf("*"),
        )
        assertEquals(
            2,
            resolveAgentTools(agent, listOf("read_window_tree", "shell")).size,
        )
        assertTrue(ConversationAgentRuntime.isSensitiveTool("shell"))
        assertTrue(ConversationAgentRuntime.isSensitiveTool("send_sms"))
    }
}
