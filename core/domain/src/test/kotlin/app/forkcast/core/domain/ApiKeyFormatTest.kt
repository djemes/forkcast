package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiKeyFormatTest {

    private val classicGemini = "AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY"
    private val aqGemini = "AQ.Ab8RN6Kq-new-format-key-0123456789abcdef"
    private val claude = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789-ABCDEFG"

    @Test fun `both gemini key formats look right`() {
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, classicGemini)).isEqualTo(KeyCheck.LOOKS_RIGHT)
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, aqGemini)).isEqualTo(KeyCheck.LOOKS_RIGHT)
    }

    @Test fun `an unknown gemini prefix is unfamiliar, not wrong`() {
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, "XY.some-future-google-key-format-123")).isEqualTo(KeyCheck.UNFAMILIAR)
    }

    @Test fun `claude keys start with sk-ant`() {
        assertThat(ApiKeyFormat.check(KeyService.CLAUDE, claude)).isEqualTo(KeyCheck.LOOKS_RIGHT)
        assertThat(ApiKeyFormat.check(KeyService.CLAUDE, "abcdefghijklmnopqrstuvwxyz")).isEqualTo(KeyCheck.UNFAMILIAR)
    }

    @Test fun `another service's key is named`() {
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, claude)).isEqualTo(KeyCheck.CLAUDE_KEY)
        assertThat(ApiKeyFormat.check(KeyService.CLAUDE, classicGemini)).isEqualTo(KeyCheck.GEMINI_KEY)
        assertThat(ApiKeyFormat.check(KeyService.CLAUDE, "sk-proj-abcdefghijklmnopqrstuvwxyz")).isEqualTo(KeyCheck.OPENAI_KEY)
        assertThat(ApiKeyFormat.check(KeyService.USDA, aqGemini)).isEqualTo(KeyCheck.GEMINI_KEY)
    }

    @Test fun `openai-compatible servers take any key`() {
        assertThat(ApiKeyFormat.check(KeyService.OPENAI, classicGemini)).isEqualTo(KeyCheck.LOOKS_RIGHT)
        assertThat(ApiKeyFormat.check(KeyService.OPENAI, "sk-or-v1-abcdefghijklmnopqrstuvwxyz")).isEqualTo(KeyCheck.LOOKS_RIGHT)
    }

    @Test fun `paste mistakes`() {
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, "   ")).isEqualTo(KeyCheck.EMPTY)
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, "AIzaSy")).isEqualTo(KeyCheck.TOO_SHORT)
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, "API key: $classicGemini")).isEqualTo(KeyCheck.HAS_SPACE)
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, "https://aistudio.google.com/apikey")).isEqualTo(KeyCheck.NOT_A_KEY)
        assertThat(ApiKeyFormat.check(KeyService.USDA, "someone@example.com")).isEqualTo(KeyCheck.NOT_A_KEY)
    }

    @Test fun `copying extras are cleaned off`() {
        val pasted = "\u200B \"$classicGemini\"\n"
        assertThat(ApiKeyFormat.clean(pasted)).isEqualTo(classicGemini)
        assertThat(ApiKeyFormat.check(KeyService.GEMINI, pasted)).isEqualTo(KeyCheck.LOOKS_RIGHT)
    }

    @Test fun `usda keys have no prefix`() {
        assertThat(ApiKeyFormat.check(KeyService.USDA, "a".repeat(40))).isEqualTo(KeyCheck.LOOKS_RIGHT)
    }
}
