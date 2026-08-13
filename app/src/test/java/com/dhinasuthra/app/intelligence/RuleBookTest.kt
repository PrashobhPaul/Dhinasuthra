package com.dhinasuthra.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rule Book is a product surface, not a comment. These tests keep it honest:
 * unique ids (so an explanation can never be ambiguous), a statement worth
 * reading, and a named enforcement site for every rule the app advertises.
 */
class RuleBookTest {

    @Test
    fun `rule ids are unique`() {
        assertEquals(emptyList<String>(), RuleBook.duplicateIds())
    }

    @Test
    fun `every rule declares where it is enforced`() {
        val missing = RuleBook.all.filter { it.enforcedIn.isBlank() }.map { it.id }
        assertEquals("rules with no enforcement site", emptyList<String>(), missing)
    }

    @Test
    fun `every rule states something specific`() {
        val vague = RuleBook.all.filter { it.statement.length < 40 || it.title.length < 8 }
        assertEquals(emptyList<Rule>(), vague)
    }

    @Test
    fun `ids follow their domain prefix`() {
        val wrong = RuleBook.all.filter { !it.id.startsWith("${it.domain.code}-") }
        assertEquals(emptyList<Rule>(), wrong)
    }

    @Test
    fun `every domain contributes rules`() {
        RuleDomain.entries.forEach { domain ->
            assertTrue("$domain has no rules", RuleBook.byDomain[domain].orEmpty().isNotEmpty())
        }
    }

    @Test
    fun `evidence rules carry weight and guards do not`() {
        RuleBook.all.forEach { rule ->
            when (rule.kind) {
                RuleKind.EVIDENCE -> assertTrue("${rule.id} has no weight", rule.weight > 0f)
                RuleKind.GUARD, RuleKind.STRUCTURAL -> assertEquals(
                    "${rule.id} should not carry evidence weight", 0f, rule.weight, 0.0001f
                )
                RuleKind.NARRATIVE -> Unit
            }
        }
    }

    @Test
    fun `search finds rules by id, title and text`() {
        assertTrue(RuleBook.search("SLP-01").isNotEmpty())
        assertTrue(RuleBook.search("lunch").isNotEmpty())
        assertTrue(RuleBook.search("geofence").isNotEmpty())
        assertTrue(RuleBook.search("zzzz-not-a-rule").isEmpty())
    }

    @Test
    fun `noisy-OR confidence is monotonic and bounded`() {
        val ledger = EvidenceLedger()
        assertEquals(0f, ledger.confidence(), 0.0001f)

        ledger.add(MealRules.LEARNED_START, "detail")
        val one = ledger.confidence()
        ledger.add(MealRules.LEARNED_DURATION, "detail")
        val two = ledger.confidence()

        assertTrue("more evidence must not lower confidence", two > one)
        assertTrue("confidence saturates below certainty", two < 1f)
    }

    @Test
    fun `a guard caps confidence however much evidence accumulates`() {
        val ledger = EvidenceLedger()
        repeat(6) { ledger.add(MealRules.LEARNED_START, "detail") }
        assertTrue(ledger.confidence() > 0.9f)

        ledger.cap(MealRules.MEAL_LENGTH_CAP, "guard fired", 0.5f)
        assertEquals(0.5f, ledger.confidence(), 0.0001f)
    }

    @Test
    fun `confidence bands are ordered`() {
        assertEquals(ConfidenceBand.UNCERTAIN, ConfidenceBand.of(0.2f))
        assertEquals(ConfidenceBand.POSSIBLE, ConfidenceBand.of(0.5f))
        assertEquals(ConfidenceBand.LIKELY, ConfidenceBand.of(0.7f))
        assertEquals(ConfidenceBand.CONFIDENT, ConfidenceBand.of(0.95f))
    }
}
