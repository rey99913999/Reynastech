package io.github.nastechresearch.nastech.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrategistConsultationBudgetTest {
    @Test
    fun zeroLimitDisablesConsultations() {
        val budget = StrategistConsultationBudget(0L)

        assertNull(budget.reserve())
        assertEquals(0L, budget.usedCount())
    }

    @Test
    fun finiteLimitAllowsExactlyConfiguredNumber() {
        val budget = StrategistConsultationBudget(92L)

        repeat(92) { expected ->
            assertEquals((expected + 1).toLong(), budget.reserve())
        }
        assertNull(budget.reserve())
        assertEquals(92L, budget.usedCount())
    }

    @Test
    fun nullLimitAllowsUnlimitedConsultations() {
        val budget = StrategistConsultationBudget(null)

        repeat(10_000) { expected ->
            assertEquals((expected + 1).toLong(), budget.reserve())
        }
        assertEquals(10_000L, budget.usedCount())
    }
}
