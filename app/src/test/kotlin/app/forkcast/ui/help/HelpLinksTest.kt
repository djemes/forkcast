package app.forkcast.ui.help

import androidx.lifecycle.SavedStateHandle
import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.health.HealthError
import app.forkcast.core.model.CheckFailure
import app.forkcast.core.network.FoodApiError
import app.forkcast.core.network.ai.AiError
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

/** Every error the app can show links to a help entry (roadmap 2.0 task 3.4 acceptance). */
class HelpLinksTest {

    private val aiErrors = listOf(
        AiError.InvalidKey(), AiError.InvalidKey(AiError.NO_KEY), AiError.InvalidKey(AiError.NEEDS_WORKSPACE),
        AiError.RateLimited(null), AiError.QuotaExhausted(null), AiError.ModelNotFound("m"), AiError.ContentRefused(),
        AiError.BadResponse(null), AiError.InvalidRequest(null), AiError.Network(), AiError.Timeout(), AiError.Server(503),
    )

    private val foodErrors = listOf(
        FoodApiError.Offline(), FoodApiError.RateLimited(), FoodApiError.InvalidKey(), FoodApiError.Server(500), FoodApiError.BadResponse(null),
    )

    @Test fun theListsAboveCoverEveryErrorClass() {
        // A new subclass fails here until it's in the list, and so until it has an entry below.
        assertThat(aiErrors.map { it::class.java }.toSet()).containsExactlyElementsIn(subclasses(AiError::class.java))
        assertThat(foodErrors.map { it::class.java }.toSet()).containsExactlyElementsIn(subclasses(FoodApiError::class.java))
    }

    @Test fun everyStoredAiCodeHasAnEntry() {
        val constants = AiErrorCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertThat(constants).containsAtLeast(AiErrorCodes.NO_PROFILE, AiErrorCodes.DISABLED, AiErrorCodes.STOPPED, AiErrorCodes.OK)
        val codes = constants + aiErrors.map(AiErrorCodes::of)
        for (code in codes - AiErrorCodes.OK) assertThat(HelpLinks.aiCode(code)).isNotNull()
        assertThat(HelpLinks.aiCode(AiErrorCodes.OK)).isNull()
    }

    @Test fun aSavedEstimateLinksWhereTheLiveErrorDoes() {
        for (e in aiErrors) assertThat(HelpLinks.aiCode(AiErrorCodes.of(e), e.message)).isEqualTo(HelpLinks.ai(e))
    }

    @Test fun aiErrorsLinkToTheirOwnAnswers() {
        assertThat(HelpLinks.ai(AiError.InvalidKey())).isEqualTo(HelpEntry.AI_KEY_REJECTED)
        assertThat(HelpLinks.ai(AiError.InvalidKey(AiError.NO_KEY))).isEqualTo(HelpEntry.AI_NOT_SET_UP)
        assertThat(HelpLinks.ai(AiError.QuotaExhausted(null))).isEqualTo(HelpEntry.AI_LIMITS)
        assertThat(HelpLinks.ai(IllegalStateException())).isEqualTo(HelpEntry.AI_ODD_ANSWER)
        assertThat(HelpLinks.aiCode(AiErrorCodes.NO_PROFILE)).isEqualTo(HelpEntry.AI_NOT_SET_UP)
    }

    @Test fun foodErrorsLinkToSearchOrTheUsdaKey() {
        for (e in foodErrors) {
            val expected = if (e is FoodApiError.InvalidKey) HelpEntry.LOG_USDA_KEY else HelpEntry.LOG_SEARCH_FAILED
            assertThat(HelpLinks.food(e)).isEqualTo(expected)
        }
        assertThat(HelpLinks.food(IllegalStateException() as Throwable)).isEqualTo(HelpEntry.LOG_SEARCH_FAILED)
    }

    @Test fun everyOtherErrorKindHasAnEntry() {
        // Exhaustive `when`s already need one per value; this keeps each in its topic.
        HealthError.entries.forEach { assertThat(HelpLinks.health(it).topic).isEqualTo(HelpTopic.HEALTH_CONNECT) }
        FolderProblem.entries.forEach { assertThat(HelpLinks.folder(it).topic).isEqualTo(HelpTopic.BACKUP) }
        RejectReason.entries.forEach { assertThat(HelpLinks.restore(it).topic).isEqualTo(HelpTopic.BACKUP) }
        (CheckFailure.entries + null).forEach { assertThat(HelpLinks.check(it).topic).isEqualTo(HelpTopic.AI) }
    }

    @Test fun idsAreStableAndEveryTopicHasEntries() {
        HelpEntry.entries.forEach { assertThat(HelpEntry.byId(it.name)).isEqualTo(it) }
        assertThat(HelpEntry.byId("NOT_AN_ENTRY")).isNull()
        assertThat(HelpEntry.byId(null)).isNull()
        assertThat(HelpEntry.entries.map { it.topic }.toSet()).containsExactlyElementsIn(HelpTopic.entries)
        assertThat(HelpEntry.entries.filter { it.topic == HelpTopic.GLOSSARY })
            .containsExactly(HelpEntry.TERM_KCAL, HelpEntry.TERM_MACROS, HelpEntry.TERM_TDEE, HelpEntry.TERM_DV)
    }

    @Test fun aLinkOpensItsEntryOnceAndExpanded() {
        val saved = SavedStateHandle(mapOf("entry" to HelpEntry.AI_LIMITS.name))
        val vm = HelpViewModel(saved)
        assertThat(vm.state.value.focus).isEqualTo(HelpEntry.AI_LIMITS)
        assertThat(vm.state.value.expanded).containsExactly(HelpEntry.AI_LIMITS)
        vm.onEvent(HelpEvent.FocusShown)
        vm.onEvent(HelpEvent.Toggle(HelpEntry.AI_LIMITS))
        vm.onEvent(HelpEvent.Toggle(HelpEntry.TERM_KCAL))
        assertThat(vm.state.value.expanded).containsExactly(HelpEntry.TERM_KCAL)
        // Recreated (rotation): the place is kept, no second jump.
        assertThat(HelpViewModel(saved).state.value.focus).isNull()
    }

    @Test fun searchMatchesEveryWordIgnoringCaseAndAccents() {
        val texts = listOf(
            HelpText(HelpEntry.BUDGET_ADAPTIVE, "Por que a minha manutenção mudou?", "A fórmula é um ponto de partida."),
            HelpText(HelpEntry.AI_LIMITS, "It says the AI is busy", "Daily limits reset at midnight Pacific time."),
        )
        assertThat(HelpSearch.filter(texts, "  ")).isEqualTo(texts)
        assertThat(HelpSearch.filter(texts, "manutencao").map { it.entry }).containsExactly(HelpEntry.BUDGET_ADAPTIVE)
        assertThat(HelpSearch.filter(texts, "FORMULA").map { it.entry }).containsExactly(HelpEntry.BUDGET_ADAPTIVE)
        assertThat(HelpSearch.filter(texts, "midnight  busy").map { it.entry }).containsExactly(HelpEntry.AI_LIMITS)
        assertThat(HelpSearch.filter(texts, "midnight formula")).isEmpty()
    }

    private fun subclasses(base: Class<*>): Set<Class<*>> =
        base.declaredClasses.filter { base.isAssignableFrom(it) && it != base }.toSet()
}
