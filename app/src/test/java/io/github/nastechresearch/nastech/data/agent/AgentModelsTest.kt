package io.github.nastechresearch.nastech.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelsTest {

    @Test
    fun `default workspace contains planner executor verifier in order`() {
        val agents = defaultAgentDefinitions()
        assertEquals(
            listOf(AgentRole.PLANNER, AgentRole.EXECUTOR, AgentRole.VERIFIER),
            agents.map { it.role },
        )
        val workflow = defaultWorkflow(agents)
        assertEquals(2, workflow.size)
        assertEquals(agents[0].id, workflow[0].fromAgentId)
        assertEquals(agents[1].id, workflow[0].toAgentId)
        assertEquals(agents[1].id, workflow[1].fromAgentId)
        assertEquals(agents[2].id, workflow[1].toAgentId)
    }

    @Test
    fun `normalized workflow rejects unknown edges and rebuilds the safe default`() {
        val agents = defaultAgentDefinitions()
        val config = ConversationAgentConfig(
            conversationId = "conversation-a",
            workflow = listOf(
                AgentWorkflowEdge("unknown-a", "unknown-b"),
            ),
        )
        val normalized = config.normalizedWorkflow()
        assertEquals(defaultWorkflow(agents).size, normalized.size)
        assertTrue(normalized.all { edge -> agents.any { it.id == edge.fromAgentId && it.id == edge.toAgentId } })
    }

    @Test
    fun `different conversations own independent configuration values`() {
        val first = ConversationAgentConfig.defaultFor("conversation-a")
        val second = ConversationAgentConfig.defaultFor("conversation-b")

        assertNotEquals(first.conversationId, second.conversationId)
        assertTrue(first.copy(instructions = "A").instructions != second.instructions)
    }
}
