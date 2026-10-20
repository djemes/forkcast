package app.forkcast.core.domain

import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixture strings follow the FDA 2016 Nutrition Facts layout, written the way ML Kit returns
 * text (one block per line, %DV columns, typical misreads). Worth extending with OCR output
 * from real labels photographed on the phone.
 */
class NutritionLabelParserTest {

    private val standard = """
        Nutrition Facts
        8 servings per container
        Serving size 2/3 cup (55g)
        Amount per serving
        Calories 230
        % Daily Value*
        Total Fat 8g 10%
        Saturated Fat 1g 5%
        Trans Fat 0g
        Cholesterol 0mg 0%
        Sodium 160mg 7%
        Total Carbohydrate 37g 13%
        Dietary Fiber 4g 14%
        Total Sugars 12g
        Includes 10g Added Sugars 20%
        Protein 3g
        Vitamin D 2mcg 10%
        Calcium 260mg 20%
        Iron 8mg 45%
        Potassium 240mg 6%
    """.trimIndent()

    @Test fun `standard fda label`() {
        val p = NutritionLabelParser.parse(standard)
        assertThat(p.servingLabel).isEqualTo("2/3 cup")
        assertThat(p.servingGrams).isEqualTo(55.0)
        assertThat(p.isUsable).isTrue()
        val n = p.perServing
        assertThat(n[NutrientKey.ENERGY_KCAL]).isEqualTo(230.0)
        assertThat(n[NutrientKey.FAT_G]).isEqualTo(8.0)
        assertThat(n[NutrientKey.SAT_FAT_G]).isEqualTo(1.0)
        assertThat(n[NutrientKey.TRANS_FAT_G]).isEqualTo(0.0)
        assertThat(n[NutrientKey.CHOLESTEROL_MG]).isEqualTo(0.0)
        assertThat(n[NutrientKey.SODIUM_MG]).isEqualTo(160.0)
        assertThat(n[NutrientKey.CARBS_G]).isEqualTo(37.0)
        assertThat(n[NutrientKey.FIBER_G]).isEqualTo(4.0)
        assertThat(n[NutrientKey.SUGARS_G]).isEqualTo(12.0)
        assertThat(n[NutrientKey.ADDED_SUGARS_G]).isEqualTo(10.0)
        assertThat(n[NutrientKey.PROTEIN_G]).isEqualTo(3.0)
        assertThat(n[NutrientKey.VITAMIN_D_UG]).isEqualTo(2.0)
        assertThat(n[NutrientKey.CALCIUM_MG]).isEqualTo(260.0)
        assertThat(n[NutrientKey.IRON_MG]).isEqualTo(8.0)
        assertThat(n[NutrientKey.POTASSIUM_MG]).isEqualTo(240.0)
    }

    @Test fun `added sugars with the amount on the next line`() {
        val p = NutritionLabelParser.parse(
            """
            Serving size 1 bar (40g)
            Calories 190
            Total Sugars 12g
            Includes Added Sugars
            9g 18%
            Protein 3g
            """.trimIndent(),
        )
        assertThat(p.perServing[NutrientKey.ADDED_SUGARS_G]).isEqualTo(9.0)
    }

    @Test fun `noisy ocr - O for zero, l for one, split lines, missing spaces`() {
        val noisy = """
            Nutrition Facts
            Serving Size 1 bar (40 g)
            Calories
            190
            Total Fat 7g
            Saturated Fat 3.5g
            Trans Fat Og
            Cholesterol 5mg
            Sodium190mg
            Total Carbohydrate
            27g
            Dietary Fiber lg
            Total Sugars 13g
            Protein 4g
        """.trimIndent()
        val p = NutritionLabelParser.parse(noisy)
        assertThat(p.servingGrams).isEqualTo(40.0)
        assertThat(p.servingLabel).isEqualTo("1 bar")
        assertThat(p.perServing[NutrientKey.ENERGY_KCAL]).isEqualTo(190.0)
        assertThat(p.perServing[NutrientKey.TRANS_FAT_G]).isEqualTo(0.0)
        assertThat(p.perServing[NutrientKey.SAT_FAT_G]).isEqualTo(3.5)
        assertThat(p.perServing[NutrientKey.FAT_G]).isEqualTo(7.0)
        assertThat(p.perServing[NutrientKey.SODIUM_MG]).isEqualTo(190.0)
        assertThat(p.perServing[NutrientKey.CARBS_G]).isEqualTo(27.0)
        assertThat(p.perServing[NutrientKey.FIBER_G]).isEqualTo(1.0)
    }

    @Test fun `side by side layout on one line`() {
        val compact = "Serving size 1 cup (240mL) Calories 150 Total Fat 8g Sat. Fat 5g Sodium 120mg Total Carb. 12g Protein 8g"
        val p = NutritionLabelParser.parse(compact)
        assertThat(p.servingGrams).isEqualTo(240.0)
        assertThat(p.perServing[NutrientKey.ENERGY_KCAL]).isEqualTo(150.0)
        assertThat(p.perServing[NutrientKey.FAT_G]).isEqualTo(8.0)
        assertThat(p.perServing[NutrientKey.SAT_FAT_G]).isEqualTo(5.0)
        assertThat(p.perServing[NutrientKey.SODIUM_MG]).isEqualTo(120.0)
        assertThat(p.perServing[NutrientKey.CARBS_G]).isEqualTo(12.0)
        assertThat(p.perServing[NutrientKey.PROTEIN_G]).isEqualTo(8.0)
    }

    @Test fun `calories from fat is not calories`() {
        val old = "Serving Size 30g\nCalories from Fat 45\nCalories 120\nTotal Fat 5g"
        assertThat(NutritionLabelParser.parse(old).perServing[NutrientKey.ENERGY_KCAL]).isEqualTo(120.0)
        assertThat(NutritionLabelParser.parse(old).servingGrams).isEqualTo(30.0)
    }

    @Test fun `less-than values and decimals`() {
        val p = NutritionLabelParser.parse("Serving size 1 tbsp (15g)\nCalories 45\nTotal Fat 5g\nSodium <5mg\nProtein 0.5g")
        assertThat(p.perServing[NutrientKey.SODIUM_MG]).isEqualTo(5.0)
        assertThat(p.perServing[NutrientKey.PROTEIN_G]).isEqualTo(0.5)
    }

    @Test fun `garbage gives an unusable result rather than a crash`() {
        val p = NutritionLabelParser.parse("INGREDIENTS: WHOLE GRAIN OATS, SUGAR, SALT")
        assertThat(p.isUsable).isFalse()
        assertThat(p.perServing).isEmpty()
    }
}
