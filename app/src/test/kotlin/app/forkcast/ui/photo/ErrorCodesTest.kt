package app.forkcast.ui.photo

import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.network.ai.AiError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A stored error code must rebuild the same error, including in release builds where R8 renames classes. */
class ErrorCodesTest {
    @Test fun everyErrorRoundTripsThroughItsStoredCode() {
        val errors = listOf(
            AiError.InvalidKey(), AiError.RateLimited(null), AiError.QuotaExhausted(null), AiError.ModelNotFound("m"),
            AiError.ContentRefused(), AiError.BadResponse(null), AiError.InvalidRequest(null), AiError.Network(),
            AiError.Timeout(), AiError.Server(503),
        )
        for (e in errors) {
            val code = AiErrorCodes.of(e)
            assertThat(errorFromCode(code, e.message)).isInstanceOf(e::class.java)
        }
    }
}
