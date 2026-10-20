package app.forkcast.core.domain

import app.forkcast.core.model.Sex
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaterMathTest {

    @Test fun glassesPlusCountedDrinks() {
        assertThat(WaterMath.dayTotalMl(listOf(250.0, 250.0), listOf(237.0, null))).isEqualTo(737.0)
        assertThat(WaterMath.dayTotalMl(emptyList(), emptyList())).isEqualTo(0.0)
    }

    @Test fun goalBySexUnlessSet() {
        assertThat(WaterMath.targetMl(null, Sex.FEMALE)).isEqualTo(2000.0)
        assertThat(WaterMath.targetMl(null, Sex.MALE)).isEqualTo(2500.0)
        assertThat(WaterMath.targetMl(null, null)).isEqualTo(2500.0)
        assertThat(WaterMath.targetMl(3000.0, Sex.FEMALE)).isEqualTo(3000.0)
    }

    @Test fun glassByUnitsUnlessSet() {
        assertThat(WaterMath.glassMl(null, flOz = false)).isEqualTo(250.0)
        assertThat(WaterMath.glassMl(null, flOz = true)).isWithin(1e-6).of(236.5882365)
        assertThat(WaterMath.glassMl(330.0, flOz = true)).isEqualTo(330.0)
    }
}
