package com.dhinasuthra.app.ui.rules

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.intelligence.Rule
import com.dhinasuthra.app.intelligence.RuleBook
import com.dhinasuthra.app.intelligence.RuleDomain
import com.dhinasuthra.app.intelligence.RuleKind
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.foundation.StatTile
import com.dhinasuthra.app.ui.theme.DsTokens
import kotlin.math.roundToInt

/**
 * The Rule Book.
 *
 * This screen exists because the app claims to be intelligent, and a claim like
 * that should be checkable. Everything DhinaSuthra concludes comes from these
 * rules; each one names the class that enforces it, so the catalogue cannot
 * quietly drift away from the code. There is no model here to inspect — this
 * *is* the model.
 */
@Composable
fun RuleBookScreen(onBack: (() -> Unit)? = null) {
    var query by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf<RuleDomain?>(null) }

    val rules = remember(query, domain) {
        RuleBook.search(query).filter { domain == null || it.domain == domain }
    }

    DsScreen(
        title = "Rule Book",
        subtitle = "${RuleBook.count} rules, all of them running on this phone",
        trailing = onBack?.let { back ->
            {
                Text(
                    "Back",
                    style = MaterialTheme.typography.labelLarge.copy(color = DsTokens.Gold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(DsTokens.Hairline)
                        .clickable { back() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                CardBody {
                    Text(
                        "How DhinaSuthra thinks",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "There is no language model, no trained network and no server. Every conclusion — this looks like lunch, you are probably awake, this pattern is established — is produced by a deterministic rule you can read below, combined with your own history.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Rules", "${RuleBook.count}", Modifier.weight(1f))
                        StatTile(
                            "Domains", "${RuleDomain.entries.size}", Modifier.weight(1f),
                            accent = DsTokens.Violet
                        )
                        StatTile(
                            "Guards",
                            "${RuleBook.all.count { it.kind == RuleKind.GUARD }}",
                            Modifier.weight(1f),
                            accent = DsTokens.Rose,
                            caption = "things it refuses to do"
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search rules") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DsChip("All", domain == null, { domain = null })
                RuleDomain.entries.forEach { d ->
                    DsChip(
                        d.label, domain == d, { domain = if (domain == d) null else d },
                        accent = DsTokens.colorFor(d)
                    )
                }
            }
        }

        if (domain != null) {
            item {
                Text(
                    domain!!.summary,
                    style = MaterialTheme.typography.bodyMedium.copy(color = DsTokens.InkSoft)
                )
            }
        }

        val grouped = rules.groupBy { it.domain }
        grouped.keys.sortedBy { it.ordinal }.forEach { d ->
            val list = grouped[d].orEmpty().sortedBy { it.id }
            item(key = "header-${d.code}") {
                SectionTitle("${d.label} · ${d.code}", "${list.size} rules")
            }
            items(list.size, key = { index -> list[index].id }) { index ->
                Reveal(index.coerceAtMost(6)) { RuleCard(list[index]) }
            }
        }

        if (rules.isEmpty()) {
            item {
                Text(
                    "No rule matches “$query”.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                CardBody {
                    Text("How confidence is combined", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Independent pieces of evidence combine as c = 1 − Π(1 − wᵢ). More evidence never lowers confidence, no single rule can reach certainty on its own, and a guard can cap the result however much evidence has piled up. That is the whole of the arithmetic.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Hairline()
                    Text(
                        "Weights shown on each rule are those wᵢ values.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule) {
    var expanded by remember { mutableStateOf(false) }
    val color = DsTokens.colorFor(rule.domain)

    GlassCard(
        Modifier.fillMaxWidth().animateContentSize(),
        tint = color,
        onClick = { expanded = !expanded }
    ) {
        CardBody(padding = 15.dp, spacing = 7.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(rule.id, style = MaterialTheme.typography.labelSmall.copy(color = color))
                }
                Spacer(Modifier.width(9.dp))
                Text(rule.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                KindBadge(rule.kind)
            }
            Text(rule.statement, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Hairline()
                if (rule.weight > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Evidence weight",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(rule.weight * 100).roundToInt()} / 100",
                            style = MaterialTheme.typography.labelMedium.copy(color = color)
                        )
                    }
                    com.dhinasuthra.app.ui.viz.Meter(rule.weight, color = color)
                } else {
                    Text(
                        when (rule.kind) {
                            RuleKind.GUARD -> "A guard carries no weight of its own — it limits what the other rules are allowed to conclude."
                            RuleKind.STRUCTURAL -> "A structural rule shapes the timeline itself rather than arguing about evidence."
                            else -> "This rule decides what may be said, not what is true."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (rule.enforcedIn.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(color, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Enforced in ${rule.enforcedIn}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: RuleKind) {
    val color = when (kind) {
        RuleKind.EVIDENCE -> DsTokens.Cyan
        RuleKind.GUARD -> DsTokens.Rose
        RuleKind.STRUCTURAL -> DsTokens.Green
        RuleKind.NARRATIVE -> DsTokens.Violet
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(kind.label, style = MaterialTheme.typography.labelSmall.copy(color = color))
    }
}
