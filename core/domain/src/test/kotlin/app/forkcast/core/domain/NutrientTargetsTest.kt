package app.forkcast.core.domain

import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NutrientTargetsTest {

    @Test fun `default macros from a 2000 kcal budget`() {
        val t = NutrientTargets.defaults(2000, currentKg = 90.0, goalKg = 80.0)
        // protein = min(1.6*90=144, 2.2*80=176) = 144
        assertThat(t.getValue(NutrientKey.PROTEIN_G).amount).isWithin(1e-9).of(144.0)
        assertThat(t.getValue(NutrientKey.FAT_G).amount).isWithin(1e-9).of(600.0 / 9)
        // carbs = (2000 - 576 - 600) / 4 = 206
        assertThat(t.getValue(NutrientKey.CARBS_G).amount).isWithin(1e-9).of(206.0)
        assertThat(t.getValue(NutrientKey.FIBER_G).amount).isWithin(1e-9).of(28.0)
        assertThat(t.getValue(NutrientKey.SODIUM_MG).amount).isWithin(1e-9).of(2300.0)
        assertThat(t.getValue(NutrientKey.SAT_FAT_G).amount).isWithin(1e-9).of(200.0 / 9)
        assertThat(t.getValue(NutrientKey.ADDED_SUGARS_G).amount).isWithin(1e-9).of(50.0)
        assertThat(t.getValue(NutrientKey.SODIUM_MG).kind).isEqualTo(TargetKind.LIMIT)
        assertThat(t.getValue(NutrientKey.PROTEIN_G).kind).isEqualTo(TargetKind.GOAL)
    }

    @Test fun `protein is capped by goal weight`() {
        val t = NutrientTargets.defaults(1800, currentKg = 130.0, goalKg = 80.0)
        // 1.6*130 = 208 > 2.2*80 = 176
        assertThat(t.getValue(NutrientKey.PROTEIN_G).amount).isWithin(1e-9).of(176.0)
    }

    @Test fun `carbs never go negative`() {
        val t = NutrientTargets.defaults(600, currentKg = 120.0, goalKg = 110.0)
        assertThat(t.getValue(NutrientKey.CARBS_G).amount).isAtLeast(0.0)
    }

    @Test fun `overrides replace amounts and keep kind`() {
        val d = NutrientTargets.defaults(2000, 90.0, 80.0)
        val t = NutrientTargets.withOverrides(d, mapOf(NutrientKey.PROTEIN_G to 180.0, NutrientKey.SODIUM_MG to 1500.0))
        assertThat(t.getValue(NutrientKey.PROTEIN_G).amount).isEqualTo(180.0)
        assertThat(t.getValue(NutrientKey.SODIUM_MG).kind).isEqualTo(TargetKind.LIMIT)
    }

    @Test fun `micronutrient daily values are present`() {
        val t = NutrientTargets.defaults(2000, 90.0, 80.0)
        assertThat(t.getValue(NutrientKey.VITAMIN_D_UG).amount).isEqualTo(20.0)
        assertThat(t.getValue(NutrientKey.POTASSIUM_MG).amount).isEqualTo(4700.0)
    }
}
