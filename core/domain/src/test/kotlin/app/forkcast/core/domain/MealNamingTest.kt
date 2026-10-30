package app.forkcast.core.domain

import app.forkcast.core.domain.MealNaming.Part
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Locale

class MealNamingTest {

    private val slots = listOf("Breakfast", "Lunch", "Dinner", "Snacks")
    private val en = MealNaming.Joiners("%1\$s & %2\$s", "%1\$s, %2\$s & %3\$s")
    private val pt = MealNaming.Joiners("%1\$s e %2\$s", "%1\$s, %2\$s e %3\$s")

    @Test fun `slot names, times and generic words are generic`() {
        listOf(
            null, "", "  ", "Snack", "Snacks", "Lunch", "lunch", "Lunch at 13:15", "Snack 3pm", "Dinner at 7 p.m.", "Meal", "Food",
            "My lunch", "Breakfast plate", "Photo meal", "Described meal", "Almoço às 13h15", "Lanche", "Refeição", "Café da manhã",
        ).forEach { assertWithMessage(it.toString()).that(MealNaming.isGeneric(it, slots + "Café da manhã")).isTrue() }
    }

    @Test fun `names of food are not generic`() {
        listOf(
            "Banana", "Chipotle chicken bowl", "Turkey and swiss sandwich", "Greek yogurt with berries", "2 eggs on toast",
            "7-Eleven hot dog", "Café com leite", "Lunch bowl", "Snack mix", "Coffee",
        ).forEach { assertWithMessage(it).that(MealNaming.isGeneric(it, slots)).isFalse() }
    }

    @Test fun `the user's own meal names count, in any language`() {
        assertThat(MealNaming.isGeneric("Pre-workout", listOf("Pre-workout"))).isTrue()
        assertThat(MealNaming.isGeneric("Ceia", listOf("Ceia"))).isTrue()
    }

    @Test fun `a good model name is kept as it is`() {
        val name = MealNaming.name("Chipotle chicken bowl", listOf(Part("Rice", 200.0)), slots, en, Locale.US)
        assertThat(name).isEqualTo("Chipotle chicken bowl")
    }

    @Test fun `a single item is named after itself`() {
        assertThat(MealNaming.name("Snack", listOf(Part("Banana", 105.0)), slots, en, Locale.US)).isEqualTo("Banana")
    }

    @Test fun `several items give the top three by calories, hidden ingredients left out`() {
        val parts = listOf(
            Part("Rice", 210.0), Part("Chicken", 180.0), Part("Olive oil", 120.0, hidden = true),
            Part("Guacamole", 230.0), Part("Salsa", 15.0),
        )
        assertThat(MealNaming.name("Lunch at 13:15", parts, slots, en, Locale.US)).isEqualTo("Guacamole, rice & chicken")
        assertThat(MealNaming.name(null, parts.take(2), slots, en, Locale.US)).isEqualTo("Rice & chicken")
    }

    @Test fun `joined in the app's language`() {
        val parts = listOf(Part("Frango", 300.0), Part("Arroz", 200.0), Part("Feijão", 150.0))
        assertThat(MealNaming.name("Almoço", parts, listOf("Almoço"), pt, Locale.forLanguageTag("pt-BR"))).isEqualTo("Frango, arroz e feijão")
    }

    @Test fun `brands and acronyms keep their capitals after the first part`() {
        val parts = listOf(Part("Ribs", 500.0), Part("BBQ sauce", 80.0))
        assertThat(MealNaming.name("Dinner", parts, slots, en, Locale.US)).isEqualTo("Ribs & BBQ sauce")
    }

    @Test fun `only hidden ingredients still give a name, and nothing gives null`() {
        assertThat(MealNaming.name("", listOf(Part("Butter", 100.0, hidden = true)), slots, en, Locale.US)).isEqualTo("Butter")
        assertThat(MealNaming.name("Meal", emptyList(), slots, en, Locale.US)).isNull()
    }
}
