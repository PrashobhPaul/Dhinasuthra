package com.dhinasuthra.app.intelligence

/**
 * The DhinaSuthra Rule Book — the whole of the app's intelligence, written down.
 *
 * DhinaSuthra has no LLM, no model file, no cloud inference (spec §35). Everything
 * it "understands" is a deterministic rule that can be printed, read, argued with
 * and unit-tested. This file defines the vocabulary; the domain files
 * (SleepRules, MealRules, WorkRules, LocationRules, TimelineRules, RoutineRules,
 * ReminderRules, AnomalyRules, ConsistencyRules, NarrativeRules) declare the rules
 * themselves, and the engines evaluate them.
 *
 * Two invariants make the "intelligence" trustworthy rather than decorative:
 *
 *  1. Every rule in [RuleBook.all] is evaluated somewhere in the engine. The Rule
 *     Book screen is a window onto real behaviour, not marketing copy.
 *  2. Every inference carries the [EvidenceLedger] that produced it, so the UI can
 *     always answer "why do you think that?" with the exact rules that fired.
 */

enum class RuleKind(val label: String) {
    /** Contributes positive evidence toward a conclusion. */
    EVIDENCE("Evidence"),

    /** Refuses or caps a conclusion regardless of accumulated evidence. */
    GUARD("Guard"),

    /** Shapes the timeline itself: merge, split, discard, fill. */
    STRUCTURAL("Structure"),

    /** Decides whether something is worth saying to the user. */
    NARRATIVE("Narrative")
}

enum class RuleDomain(val code: String, val label: String, val summary: String) {
    SLEEP("SLP", "Sleep & wake", "Separates the intent to wake from actually being awake."),
    MEAL("MEA", "Meals", "Learns when you eat instead of assuming a clock time."),
    WORK("WRK", "Work decomposition", "Office presence is never silently equal to working."),
    COMMUTE("CMT", "Commute", "Journeys are continuous, not a series of boundary crossings."),
    LOCATION("LOC", "Places & noise", "Rejects GPS flutter; a departure has to earn its evidence."),
    TIMELINE("TML", "Timeline integrity", "Keeps the day reconcilable to 24 honest hours."),
    PATTERN("PAT", "Pattern formation", "Turns repeated behaviour into a pattern with a lifecycle."),
    ROUTINE("RTN", "Routine & adherence", "Compares what you planned with what you did."),
    REMINDER("RMD", "Prompting", "A quiet day with no prompts is a success, not a failure."),
    ANOMALY("ANM", "Deviation", "Notices unusual days without moralising about them."),
    CONSISTENCY("CNS", "Consistency", "Measures how repeatable your rhythm actually is."),
    NARRATIVE("NAR", "Narration", "Decides what is worth saying, and stays silent otherwise.")
}

data class Rule(
    val id: String,
    val domain: RuleDomain,
    val kind: RuleKind,
    val title: String,
    /** Human-readable IF/THEN statement. Shown verbatim in the Rule Book. */
    val statement: String,
    /** Evidence weight in [0,1]. Guards and structural rules use 0. */
    val weight: Float = 0f,
    /** The class/function that enforces this rule — printed so the claim is checkable. */
    val enforcedIn: String = ""
) {
    val number: Int get() = id.substringAfterLast('-').toIntOrNull() ?: 0
}

/** Declaration helper so domain files stay readable. */
internal fun rule(
    id: String,
    domain: RuleDomain,
    kind: RuleKind,
    title: String,
    statement: String,
    weight: Float = 0f,
    enforcedIn: String = ""
) = Rule(id, domain, kind, title, statement, weight, enforcedIn)

/**
 * A rule that fired, with the concrete observation that made it fire.
 * `detail` is always specific ("returned to Office 42s after leaving"), never generic.
 */
data class FiredRule(val rule: Rule, val detail: String, val weight: Float)

/**
 * Accumulated evidence for one inference.
 *
 * Confidence combines independent evidence with noisy-OR:
 *
 *     c = 1 − Π(1 − wᵢ)
 *
 * which is monotonic (more evidence never lowers confidence), saturating (no single
 * rule can reach certainty), and explainable — every term maps to a printed rule.
 * Guards apply a hard ceiling afterwards, so a single decisive objection can veto
 * a pile of weak agreement.
 */
class EvidenceLedger {
    private val _fired = mutableListOf<FiredRule>()
    private var ceiling = 1f

    val fired: List<FiredRule> get() = _fired.toList()

    fun add(rule: Rule, detail: String, weight: Float = rule.weight): EvidenceLedger {
        _fired += FiredRule(rule, detail, weight.coerceIn(0f, 1f))
        return this
    }

    fun addIf(condition: Boolean, rule: Rule, detail: String, weight: Float = rule.weight): EvidenceLedger {
        if (condition) add(rule, detail, weight)
        return this
    }

    /** A guard caps the final confidence — used to express "this cannot be certain". */
    fun cap(rule: Rule, detail: String, maxConfidence: Float): EvidenceLedger {
        _fired += FiredRule(rule, detail, 0f)
        ceiling = minOf(ceiling, maxConfidence.coerceIn(0f, 1f))
        return this
    }

    fun confidence(): Float {
        var inverse = 1f
        for (f in _fired) inverse *= (1f - f.weight)
        return (1f - inverse).coerceIn(0f, 1f).coerceAtMost(ceiling)
    }

    /** The hardest cap any guard has applied — callers blending in other scores must respect it. */
    fun ceiling(): Float = ceiling

    /**
     * Rule ids in fire order, e.g. "SLP-03, SLP-07". Kept on episodes so a
     * conclusion can be traced back in tests and logs — never shown to the user,
     * who cares about the finding rather than the machinery behind it.
     */
    fun ruleIds(): List<String> = _fired.map { it.rule.id }

    /**
     * The human half of the ledger: plain sentences, no rule numbers. This is
     * what the interface shows when someone asks why.
     */
    fun explanations(): List<String> = _fired.map { it.detail }

    /** Id-prefixed form, for test failure messages and debug logging only. */
    fun traced(): List<String> = _fired.map { "${it.rule.id} · ${it.detail}" }

    fun isEmpty(): Boolean = _fired.isEmpty()

    operator fun plus(other: EvidenceLedger): EvidenceLedger {
        val merged = EvidenceLedger()
        _fired.forEach { merged._fired += it }
        other._fired.forEach { merged._fired += it }
        merged.ceiling = minOf(ceiling, other.ceiling)
        return merged
    }
}

/** Confidence bands used consistently across UI and engines. */
enum class ConfidenceBand(val label: String, val floor: Float) {
    UNCERTAIN("Uncertain", 0f),
    POSSIBLE("Possible", 0.4f),
    LIKELY("Likely", 0.62f),
    CONFIDENT("Confident", 0.8f);

    companion object {
        fun of(confidence: Float): ConfidenceBand = when {
            confidence >= CONFIDENT.floor -> CONFIDENT
            confidence >= LIKELY.floor -> LIKELY
            confidence >= POSSIBLE.floor -> POSSIBLE
            else -> UNCERTAIN
        }
    }
}

/**
 * The registry. Every domain contributes its rules here, and the Rule Book screen
 * renders exactly this list — so the catalogue can never drift from the engine.
 */
object RuleBook {

    val all: List<Rule> by lazy {
        SleepRules.rules +
            MealRules.rules +
            WorkRules.rules +
            CommuteRules.rules +
            LocationRules.rules +
            TimelineRules.rules +
            PatternRules.rules +
            RoutineRules.rules +
            ReminderRules.rules +
            AnomalyRules.rules +
            ConsistencyRules.rules +
            NarrativeRules.rules
    }

    val byDomain: Map<RuleDomain, List<Rule>> by lazy {
        all.groupBy { it.domain }.mapValues { (_, v) -> v.sortedBy { it.id } }
    }

    fun byId(id: String): Rule? = all.firstOrNull { it.id == id }

    fun search(query: String): List<Rule> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter {
            it.id.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                it.statement.lowercase().contains(q) ||
                it.domain.label.lowercase().contains(q)
        }
    }

    val count: Int get() = all.size

    /** Duplicate ids would make explanations ambiguous — asserted by unit test. */
    fun duplicateIds(): List<String> =
        all.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted()
}
