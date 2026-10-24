package app.forkcast.core.domain

import app.forkcast.core.model.WaterContainer
import app.forkcast.core.model.WaterContainers
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaterContainersTest {
    @Test fun defaultsAndOwnSizes() {
        // A standard 12 fl oz can (owner, 2026-10-23), the glass from Settings › Water.
        assertThat(WaterMath.containerMl(WaterContainer.CAN, null, WaterContainers(), flOz = true)).isEqualTo(355.0)
        assertThat(WaterMath.containerMl(WaterContainer.GLASS, 300.0, WaterContainers(), flOz = true)).isEqualTo(300.0)
        assertThat(WaterMath.containerMl(WaterContainer.TUMBLER, null, WaterContainers(), flOz = true)).isWithin(0.1).of(887.2)
        assertThat(WaterMath.containerMl(WaterContainer.BOTTLE, null, WaterContainers(bottleMl = 710.0), flOz = true)).isEqualTo(710.0)
    }

    /** Editing the day's total removes the newest entries first, then adds what's missing. */
    @Test fun adjustTotal() {
        val entries = listOf("a" to 250.0, "b" to 500.0, "c" to 300.0)
        assertThat(WaterMath.adjustTo(600.0, entries, drinksMl = 0.0)).isEqualTo(WaterMath.Adjustment(listOf("c", "b"), 350.0))
        assertThat(WaterMath.adjustTo(1500.0, entries, drinksMl = 0.0)).isEqualTo(WaterMath.Adjustment(emptyList(), 450.0))
        // 200 ml came from drinks: the water entries make up the rest.
        assertThat(WaterMath.adjustTo(1250.0, entries, drinksMl = 200.0)).isEqualTo(WaterMath.Adjustment(emptyList(), 0.0))
        assertThat(WaterMath.adjustTo(0.0, entries, drinksMl = 200.0)).isEqualTo(WaterMath.Adjustment(listOf("c", "b", "a"), 0.0))
    }
}
