package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GtinTest {

    @Test fun `upc-a, ean-13 and gtin-14 of the same product match`() {
        val upcA = "016000275287" // Cheerios
        assertThat(Gtin.normalize(upcA)).isEqualTo("00016000275287")
        assertThat(Gtin.normalize("0016000275287")).isEqualTo("00016000275287")
        assertThat(Gtin.normalize("00016000275287")).isEqualTo("00016000275287")
    }

    @Test fun `ean-13 from europe`() {
        assertThat(Gtin.normalize("3017624010701")).isEqualTo("03017624010701") // Nutella
    }

    @Test fun `bad check digit is rejected`() {
        assertThat(Gtin.normalize("016000275288")).isNull()
        assertThat(Gtin.normalize("3017624010702")).isNull()
    }

    @Test fun `non digits and odd lengths are rejected`() {
        assertThat(Gtin.normalize("abc")).isNull()
        assertThat(Gtin.normalize("12345")).isNull()
        assertThat(Gtin.normalize("")).isNull()
    }

    @Test fun `spaces and dashes are ignored`() {
        assertThat(Gtin.normalize(" 0 16000-27528 7 ")).isEqualTo("00016000275287")
    }

    @Test fun `upc-e expands to upc-a`() {
        // Classic examples: 04252614 → 042100005264 (Coke example from GS1 docs)
        assertThat(Gtin.upcEToUpcA("04252614")).isEqualTo("042100005264")
        assertThat(Gtin.normalizeUpcE("04252614")).isEqualTo("00042100005264")
        // Last digit 3, 4 and 5–9 rules
        assertThat(Gtin.upcEToUpcA("01234533")).isEqualTo("012300000453")
        assertThat(Gtin.upcEToUpcA("01234544")).isEqualTo("012340000054")
        assertThat(Gtin.upcEToUpcA("01234565")).isEqualTo("012345000065")
    }

    @Test fun `ean-8 stays ean-8 when valid`() {
        assertThat(Gtin.normalize("96385074")).isEqualTo("00000096385074")
    }

    @Test fun `lookup variants drop leading zeros`() {
        assertThat(Gtin.lookupVariants("00016000275287")).containsExactly("00016000275287", "0016000275287", "016000275287").inOrder()
    }
}
