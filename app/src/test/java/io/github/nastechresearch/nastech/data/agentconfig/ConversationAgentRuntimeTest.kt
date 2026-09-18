package io.github.nastechresearch.nastech.data.agentconfig

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationAgentRuntimeTest {
    private val runtime = ConversationAgentRuntime()

    @Test
    fun loopDetectionRejectsAgentCycles() {
        val config = ConversationAgentConfig(
            conversationId = "c1",
            enabled = true,
            autonomousTaskMode = true,
            agents = listOf(
                ConversationAgentDefinition("a", "Planner", AgentRole.PLANNER),
                ConversationAgentDefinition("b", "Executor", AgentRole.EXECUTOR),
            ),
            workflow = listOf(
                ConversationAgentWorkflowEdge("a", "b"),
                ConversationAgentWorkflowEdge("b", "a"),
            ),
        )
        assertTrue(runtime.hasAgentLoop(config))
    }

    @Test
    fun acyclicWorkflowIsAccepted() {
        val config = ConversationAgentConfig(
            conversationId = "c1",
            enabled = true,
            autonomousTaskMode = true,
            agents = listOf(
                ConversationAgentDefinition("a", "Planner", AgentRole.PLANNER),
                ConversationAgentDefinition("b", "Executor", AgentRole.EXECUTOR),
                ConversationAgentDefinition("c", "Verifier", AgentRole.VERIFIER),
            ),
            workflow = listOf(
                ConversationAgentWorkflowEdge("a", "b"),
                ConversationAgentWorkflowEdge("b", "c"),
            ),
        )
        assertFalse(runtime.hasAgentLoop(config))
    }

    @Test
    fun visualAgentActivatesWhenVisualInputExists() {
        val config = ConversationAgentConfig(
            conversationId = "c1",
            enabled = true,
            autonomousTaskMode = true,
            agents = listOf(
                ConversationAgentDefinition("p", "Planner", AgentRole.PLANNER),
                ConversationAgentDefinition("v", "Vision", AgentRole.VISION, activation = AgentActivation.NEEDS_VISION),
            ),
        )
        assertEquals("v", runtime.selectAgentForTurn(config, hasVisualInput = true)?.id)
    }

    @Test
    fun disabledRuntimeKeepsTraditionalMode() {
        val config = ConversationAgentConfig(
            conversationId = "c1",
            enabled = false,
            autonomousTaskMode = false,
            agents = listOf(ConversationAgentDefinition("p", "Planner", AgentRole.PLANNER)),
        )
        assertEquals(null, runtime.selectAgentForTurn(config, hasVisualInput = false))
    }
}
