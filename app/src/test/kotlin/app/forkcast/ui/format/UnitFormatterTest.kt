package app.forkcast.ui.format

import app.forkcast.core.model.UnitPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class UnitFormatterTest {

    private val us = UnitFormatter(UnitPrefs.US, Locale.US)
    private val metric = UnitFormatter(UnitPrefs.METRIC, Locale.US)

    @Test fun `weight in both systems`() {
        assertThat(us.weight(89.0)).isEqualTo("196.2 lb")
        assertThat(metric.weight(89.0)).isEqualTo("89 kg")
        assertThat(us.weightToKg(196.4)).isWithin(1e-6).of(89.085541468)
        assertThat(us.weightInput(89.0)).isEqualTo("196.2")
    }

    @Test fun `energy and grouping`() {
        assertThat(us.energy(2290.0)).isEqualTo("2,290 kcal")
        val kj = UnitFormatter(UnitPrefs.US.copy(energy = app.forkcast.core.model.EnergyUnit.KJ), Locale.US)
        assertThat(kj.energy(1000.0)).isEqualTo("4,184 kJ")
    }

    @Test fun `a glass reads in ml or fl oz, never as a fraction of a litre`() {
        assertThat(metric.waterServing(250.0)).isEqualTo("250 ml")
        assertThat(metric.waterServing(330.0)).isEqualTo("330 ml")
        assertThat(us.waterServing(236.588)).isEqualTo("8 fl oz")
    }

    @Test fun `height`() {
        assertThat(us.height(180.34)).isEqualTo("5′ 11″")
        assertThat(metric.height(180.34)).isEqualTo("180 cm")
    }

    @Test fun `parsing accepts dot and comma`() {
        assertThat(UnitFormatter.parseNumber("196.4")).isEqualTo(196.4)
        assertThat(UnitFormatter.parseNumber("196,4", Locale.GERMANY)).isEqualTo(196.4)
        assertThat(UnitFormatter.parseNumber(" 12 ")).isEqualTo(12.0)
        assertThat(UnitFormatter.parseNumber("abc")).isNull()
        assertThat(UnitFormatter.parseNumber("")).isNull()
    }

    @Test fun `locale defaults`() {
        assertThat(UnitFormatter.defaultsFor(Locale.US)).isEqualTo(UnitPrefs.US)
        assertThat(UnitFormatter.defaultsFor(Locale.forLanguageTag("pt-BR"))).isEqualTo(UnitPrefs.METRIC)
    }
}
