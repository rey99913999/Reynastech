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
            listOf(AgentRole.PLANNER, AgentRole.VISION, AgentRole.EXECUTOR, AgentRole.VERIFIER),
            agents.map { it.role },
        )
        val workflow = defaultWorkflow(agents)
        assertEquals(3, workflow.size)
        assertEquals(agents[0].id, workflow[0].fromAgentId)
        assertEquals(agents[1].id, workflow[0].toAgentId)
        assertEquals(agents[1].id, workflow[1].fromAgentId)
        assertEquals(agents[2].id, workflow[1].toAgentId)
        assertEquals(agents[2].id, workflow[2].fromAgentId)
        assertEquals(agents[3].id, workflow[2].toAgentId)
        assertEquals(AgentActivationCondition.HAS_VISUAL_SIGNAL, agents[1].activationCondition)
        assertEquals(AgentOutputType.STRUCTURED, agents[1].outputType)
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
        assertTrue(normalized.all { edge -> agents.any { it.id == edge.fromAgentId } && agents.any { it.id == edge.toAgentId } })
    }

    @Test
    fun `different conversations own independent configuration values`() {
        val first = ConversationAgentConfig.defaultFor("conversation-a")
        val second = ConversationAgentConfig.defaultFor("conversation-b")

        assertNotEquals(first.conversationId, second.conversationId)
        assertTrue(first.copy(instructions = "A").instructions != second.instructions)
    }
    @Test
    fun executorAndVerifierInstructionsTeachAsyncCompletion() {
        val agents = defaultAgentDefinitions()
        val executor = agents.first { it.role == AgentRole.EXECUTOR }
        val verifier = agents.first { it.role == AgentRole.VERIFIER }

        assertTrue(executor.systemInstructions.contains("wait_until"))
        assertTrue(executor.systemInstructions.contains("copy-to-clipboard"))
        assertTrue(verifier.systemInstructions.contains("condition_not_met"))
        assertTrue(verifier.systemInstructions.contains("clipboard"))
    }

}
