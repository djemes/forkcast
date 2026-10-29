package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortionScaleTest {

    @Test fun `1x sits in the middle, each half evenly spaced`() {
        assertThat(PortionScale.position(PortionScale.ONE)).isEqualTo(0.5)
        assertThat(PortionScale.position(0)).isEqualTo(0.0)
        assertThat(PortionScale.position(PortionScale.SNAPS.lastIndex)).isEqualTo(1.0)
        // Left half: ¼ ½ ¾ 1 at thirds; right half: 1 1¼ 1½ 2 3 at quarters.
        assertThat(PortionScale.position(1)).isWithin(1e-9).of(1.0 / 6)
        assertThat(PortionScale.position(2)).isWithin(1e-9).of(2.0 / 6)
        assertThat(PortionScale.position(4)).isWithin(1e-9).of(0.625)
        assertThat(PortionScale.position(6)).isWithin(1e-9).of(0.875)
    }

    @Test fun `a drag snaps to the nearest point`() {
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(0.0)]).isEqualTo(0.25)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(0.2)]).isEqualTo(0.5)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(0.49)]).isEqualTo(1.0)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(0.7)]).isEqualTo(1.5)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(0.95)]).isEqualTo(3.0)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(1.4)]).isEqualTo(3.0)
        assertThat(PortionScale.SNAPS[PortionScale.nearestIndex(-0.2)]).isEqualTo(0.25)
    }

    @Test fun `custom portions sit between their neighbours or at an end`() {
        assertThat(PortionScale.indexOf(0.4)).isNull()
        assertThat(PortionScale.indexOf(1.0)).isEqualTo(PortionScale.ONE)
        assertThat(PortionScale.indexOf(1.2499)).isEqualTo(4)
        // 1.75 is halfway between 1½ and 2.
        assertThat(PortionScale.positionOf(1.75)).isWithin(1e-9).of((0.75 + 0.875) / 2)
        assertThat(PortionScale.positionOf(0.1)).isEqualTo(0.0)
        assertThat(PortionScale.positionOf(5.0)).isEqualTo(1.0)
    }

    @Test fun `steps move one snap, from a custom portion to the next one that way`() {
        assertThat(PortionScale.step(1.0, up = true)).isEqualTo(1.25)
        assertThat(PortionScale.step(1.0, up = false)).isEqualTo(0.75)
        assertThat(PortionScale.step(3.0, up = true)).isEqualTo(3.0)
        assertThat(PortionScale.step(0.25, up = false)).isEqualTo(0.25)
        assertThat(PortionScale.step(1.75, up = true)).isEqualTo(2.0)
        assertThat(PortionScale.step(1.75, up = false)).isEqualTo(1.5)
        assertThat(PortionScale.step(6.0, up = false)).isEqualTo(3.0)
    }

    @Test fun `scaling starts from the stored portion`() {
        // 1½ cups logged at 1½×: 1× is 1 cup, so ½× is half a cup and nutrients a third.
        assertThat(PortionScale.quantityFor(1.5, from = 1.5, to = 0.5)).isWithin(1e-9).of(0.5)
        assertThat(PortionScale.factor(1.5, 0.5)).isWithin(1e-9).of(1.0 / 3)
        assertThat(PortionScale.quantityFor(180.0, from = 1.0, to = 2.0)).isEqualTo(360.0)
        assertThat(PortionScale.factor(1.0, 1.0)).isEqualTo(1.0)
    }

    @Test fun `typed multipliers stay in range`() {
        assertThat(PortionScale.custom(1.333)).isEqualTo(1.33)
        assertThat(PortionScale.custom(0.0)).isEqualTo(0.01)
        assertThat(PortionScale.custom(25.0)).isEqualTo(10.0)
    }
}
