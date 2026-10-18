package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnitConversionsTest {

    @Test fun `round trips are exact enough`() {
        assertThat(UnitConversions.kgToLb(UnitConversions.lbToKg(196.4))).isWithin(1e-9).of(196.4)
        assertThat(UnitConversions.lbToKg(1.0)).isWithin(1e-12).of(0.45359237)
        assertThat(UnitConversions.kcalToKj(100.0)).isWithin(1e-9).of(418.4)
        assertThat(UnitConversions.flOzToMl(8.0)).isWithin(1e-6).of(236.5882365)
        assertThat(UnitConversions.ozToG(1.0)).isWithin(1e-9).of(28.349523125)
    }

    @Test fun `feet and inches`() {
        assertThat(UnitConversions.feetInchesToCm(5, 11.0)).isWithin(1e-9).of(180.34)
        assertThat(UnitConversions.cmToFeetInches(180.34)).isEqualTo(5 to 11)
        // 5 ft 11.6 in rounds up to 6 ft 0 in
        assertThat(UnitConversions.cmToFeetInches(UnitConversions.feetInchesToCm(5, 11.6))).isEqualTo(6 to 0)
        val (ft, inch) = UnitConversions.cmToFeetAndDecimalInches(180.0)
        assertThat(ft).isEqualTo(5)
        assertThat(inch).isWithin(1e-6).of(10.866142)
    }
}
