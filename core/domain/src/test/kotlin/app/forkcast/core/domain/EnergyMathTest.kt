package app.forkcast.core.domain

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.Sex
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class EnergyMathTest {

    @Test fun `mifflin st jeor matches hand calculation`() {
        // 10*90 + 6.25*180 - 5*40 = 1825
        assertThat(EnergyMath.bmrMifflinStJeor(Sex.MALE, 90.0, 180.0, 40)).isWithin(1e-9).of(1830.0)
        assertThat(EnergyMath.bmrMifflinStJeor(Sex.FEMALE, 90.0, 180.0, 40)).isWithin(1e-9).of(1664.0)
    }

    @Test fun `age counts completed years`() {
        assertThat(EnergyMath.ageYears(LocalDate.of(1986, 10, 19), LocalDate.of(2026, 10, 18))).isEqualTo(39)
        assertThat(EnergyMath.ageYears(LocalDate.of(1986, 10, 18), LocalDate.of(2026, 10, 18))).isEqualTo(40)
        // Leap-day birthday
        assertThat(EnergyMath.ageYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 2, 28))).isEqualTo(25)
        assertThat(EnergyMath.ageYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 3, 1))).isEqualTo(26)
    }

    @Test fun `tdee uses activity factors`() {
        val bmr = 1830.0
        assertThat(EnergyMath.tdee(bmr, ActivityLevel.SEDENTARY)).isWithin(1e-9).of(2196.0)
        assertThat(EnergyMath.tdee(bmr, ActivityLevel.LIGHT)).isWithin(1e-9).of(2516.25)
        assertThat(EnergyMath.tdee(bmr, ActivityLevel.MODERATE)).isWithin(1e-9).of(2836.5)
        assertThat(EnergyMath.tdee(bmr, ActivityLevel.VERY_ACTIVE)).isWithin(1e-9).of(3156.75)
        assertThat(EnergyMath.tdee(bmr, ActivityLevel.EXTRA_ACTIVE)).isWithin(1e-9).of(3477.0)
    }

    @Test fun `budget rounds to nearest ten`() {
        assertThat(EnergyMath.roundBudget(2286.5)).isEqualTo(2290)
        assertThat(EnergyMath.roundBudget(2284.9)).isEqualTo(2280)
        assertThat(EnergyMath.roundBudget(2285.0)).isEqualTo(2290)
    }
}
