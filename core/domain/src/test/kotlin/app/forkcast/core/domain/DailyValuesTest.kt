package app.forkcast.core.domain

import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DailyValuesTest {
    /** "Vitamin C 45 mg · 50%" (owner, 2026-10-23). */
    @Test fun vitaminsAndMineralsHaveAPercentOfTheDailyValue() {
        assertThat(DailyValues.percent(NutrientKey.VITAMIN_C_MG, 45.0)).isEqualTo(50)
        assertThat(DailyValues.percent(NutrientKey.IRON_MG, 27.0)).isEqualTo(150)
        assertThat(DailyValues.percent(NutrientKey.SODIUM_MG, 1150.0)).isEqualTo(50)
        assertThat(DailyValues.percent(NutrientKey.VITAMIN_B12_UG, 0.01)).isEqualTo(0)
    }

    @Test fun macrosHaveNone() {
        assertThat(DailyValues.percent(NutrientKey.PROTEIN_G, 50.0)).isNull()
        assertThat(DailyValues.percent(NutrientKey.ENERGY_KCAL, 2000.0)).isNull()
    }
}
