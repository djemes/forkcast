package app.forkcast.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.Guardrail
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.abs

@Composable
fun BudgetSummary(
    targets: DailyTargets,
    units: UnitPrefs,
    sex: Sex,
    heightCm: Double,
    activity: ActivityLevel,
    /** Added back (SPEC §5.2): the percent of active calories added each day; null when Included. */
    addBackPct: Int? = null,
) {
    val f = UnitFormatter(units)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.budget_kcal_per_day, f.energy(targets.budgetKcal.toDouble())),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        MacroRow(targets, f)
        Guardrails(targets.guardrails, f)
        HowWeCalculated(targets, f, sex, heightCm, activity, addBackPct)
        Text(
            stringResource(R.string.not_medical_advice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MacroRow(t: DailyTargets, f: UnitFormatter) {
    val c = ForkcastTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(R.string.macro_protein, NutrientKey.PROTEIN_G, c.protein),
            Triple(R.string.macro_carbs, NutrientKey.CARBS_G, c.carbs),
            Triple(R.string.macro_fat, NutrientKey.FAT_G, c.fat),
            Triple(R.string.macro_fiber, NutrientKey.FIBER_G, c.fiber),
        ).forEach { (label, key, color) ->
            Row {
                Text("●", color = color, modifier = Modifier.padding(end = 6.dp))
                Text(
                    "${stringResource(label)} ${f.grams(t.targets[key]?.amount ?: 0.0)}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun Guardrails(guardrails: List<Guardrail>, f: UnitFormatter) {
    guardrails.forEach { g ->
        val text = when (g) {
            is Guardrail.FastLoss -> stringResource(R.string.guard_fast_loss, f.weight(g.maxKgPerWeek), formatDate(g.onePercentDate))
            is Guardrail.LowBudget -> stringResource(R.string.guard_low_budget, f.energy(g.floorKcal.toDouble()), f.energy(g.bmrKcal.toDouble()))
        }
        // Neutral tone: tertiary container, never error red (DESIGN.md: red is for errors, not eating).
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun HowWeCalculated(t: DailyTargets, f: UnitFormatter, sex: Sex, heightCm: Double, activity: ActivityLevel, addBackPct: Int?) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) {
            Icon(
                painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
            )
            Text(stringResource(R.string.how_calculated), modifier = Modifier.padding(start = 4.dp))
        }
        AnimatedVisibility(open) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                val lines = buildList {
                    add(
                        stringResource(
                            R.string.calc_bmr,
                            f.number(t.currentKg, 1),
                            f.number(heightCm, 0),
                            t.ageYears,
                            stringResource(if (sex == Sex.MALE) R.string.calc_bmr_male else R.string.calc_bmr_female),
                            f.energy(t.bmrKcal),
                        ),
                    )
                    val factor = t.formulaTdeeKcal / t.bmrKcal
                    if (addBackPct != null) {
                        add(stringResource(R.string.calc_tdee_added_back, f.number(factor, 3), f.energy(t.formulaTdeeKcal)))
                    } else {
                        add(stringResource(R.string.calc_tdee, f.number(activity.factor, 3), f.energy(t.formulaTdeeKcal)))
                    }
                    // Adaptive maintenance replaces the formula (SPEC §5.6).
                    t.adaptiveTdeeKcal?.let { adaptive ->
                        add(stringResource(R.string.calc_adaptive, f.energy(adaptive), f.energy(t.formulaTdeeKcal)))
                        if (t.avgExerciseBonusKcal > 0) add(stringResource(R.string.calc_adaptive_added_back, f.energy(t.avgExerciseBonusKcal), f.energy(t.tdeeKcal)))
                    }
                    val delta = t.plan.dailyDeltaKcal
                    val rate = t.plan.pace?.kgPerWeek
                    add(
                        when {
                            rate == null || abs(delta) < 1 -> stringResource(R.string.calc_maintain)
                            delta > 0 -> stringResource(R.string.calc_deficit, f.energy(delta), f.weight(rate))
                            else -> stringResource(R.string.calc_surplus, f.energy(-delta), f.weight(rate))
                        },
                    )
                    add(stringResource(R.string.calc_budget, f.energy(t.plan.budgetKcal.toDouble())))
                    addBackPct?.let { add(stringResource(R.string.calc_added_back_note, it)) }
                    add(stringResource(R.string.calc_macros))
                    if (t.adaptiveTdeeKcal == null) add(stringResource(R.string.calc_adaptive_note))
                }
                lines.forEach { line ->
                    Row {
                        Text("•", modifier = Modifier.width(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
