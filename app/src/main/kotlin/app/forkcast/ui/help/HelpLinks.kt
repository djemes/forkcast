package app.forkcast.ui.help

import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.backup.folder.FolderProblem
import app.forkcast.core.data.health.HealthError
import app.forkcast.core.model.CheckFailure
import app.forkcast.core.network.FoodApiError
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.update.UpdateError

/**
 * Which help entry explains each error the app can show (roadmap 2.0 task 3.4: every error links
 * to help). The `when`s have no `else` where the type is sealed, so a new error doesn't compile
 * until it has an entry; HelpLinksTest covers the stored AI codes, which are strings.
 */
object HelpLinks {

    fun ai(error: AiError): HelpEntry = when (error) {
        is AiError.InvalidKey -> if (error.missing || error.message?.contains(AiError.NO_KEY) == true) HelpEntry.AI_NOT_SET_UP else HelpEntry.AI_KEY_REJECTED
        is AiError.RateLimited, is AiError.QuotaExhausted -> HelpEntry.AI_LIMITS
        is AiError.ModelNotFound -> HelpEntry.AI_MODEL
        is AiError.ContentRefused, is AiError.BadResponse, is AiError.InvalidRequest -> HelpEntry.AI_ODD_ANSWER
        is AiError.Network, is AiError.Timeout, is AiError.Server -> HelpEntry.AI_CONNECTION
    }

    /** Any error a test or check returned; one that isn't an [AiError] is the provider answering oddly. */
    fun ai(error: Throwable?): HelpEntry = (error as? AiError)?.let { ai(it) } ?: HelpEntry.AI_ODD_ANSWER

    /**
     * An estimate's stored error code ([AiErrorCodes]) and its [detail] (the stored message, which
     * tells a missing key from a rejected one); null for [AiErrorCodes.OK], which isn't an error.
     */
    fun aiCode(code: String?, detail: String? = null): HelpEntry? = when (code) {
        AiErrorCodes.OK -> null
        AiErrorCodes.NO_PROFILE, AiErrorCodes.DISABLED -> HelpEntry.AI_NOT_SET_UP
        AiErrorCodes.STOPPED -> HelpEntry.AI_CONNECTION
        "InvalidKey" -> if (detail?.contains(AiError.NO_KEY) == true) HelpEntry.AI_NOT_SET_UP else HelpEntry.AI_KEY_REJECTED
        "RateLimited", "QuotaExhausted" -> HelpEntry.AI_LIMITS
        "ModelNotFound" -> HelpEntry.AI_MODEL
        "Network", "Timeout", "Server" -> HelpEntry.AI_CONNECTION
        // ContentRefused, BadResponse, InvalidRequest, and codes from a newer version.
        else -> HelpEntry.AI_ODD_ANSWER
    }

    /** A remembered test result (Settings › AI's "Last test failed"). */
    fun check(failure: CheckFailure?): HelpEntry = when (failure) {
        CheckFailure.KEY -> HelpEntry.AI_KEY_REJECTED
        CheckFailure.QUOTA -> HelpEntry.AI_LIMITS
        CheckFailure.MODEL -> HelpEntry.AI_MODEL
        CheckFailure.NETWORK -> HelpEntry.AI_CONNECTION
        CheckFailure.OTHER, null -> HelpEntry.AI_ODD_ANSWER
    }

    fun food(error: FoodApiError): HelpEntry = when (error) {
        is FoodApiError.InvalidKey -> HelpEntry.LOG_USDA_KEY
        is FoodApiError.Offline, is FoodApiError.RateLimited, is FoodApiError.Server, is FoodApiError.BadResponse -> HelpEntry.LOG_SEARCH_FAILED
    }

    /** A failed USDA key check: anything that isn't a [FoodApiError] is USDA not answering properly. */
    fun food(error: Throwable?): HelpEntry = (error as? FoodApiError)?.let { food(it) } ?: HelpEntry.LOG_SEARCH_FAILED

    fun health(error: HealthError): HelpEntry = when (error) {
        HealthError.UNAVAILABLE -> HelpEntry.HC_UNAVAILABLE
        HealthError.PERMISSION -> HelpEntry.HC_PERMISSIONS
        HealthError.RATE_LIMITED, HealthError.FAILED -> HelpEntry.HC_NOT_SYNCING
    }

    fun folder(problem: FolderProblem): HelpEntry = when (problem) {
        FolderProblem.NO_FOLDER, FolderProblem.FOLDER_LOST, FolderProblem.WRITE_FAILED -> HelpEntry.BACKUP_AUTO_FAILED
        FolderProblem.PASSWORD_MISSING -> HelpEntry.BACKUP_PASSWORD
    }

    fun restore(reason: RejectReason): HelpEntry = when (reason) {
        RejectReason.UNREADABLE, RejectReason.NOT_FORKCAST, RejectReason.NEWER_VERSION, RejectReason.OLD_VERSION -> HelpEntry.BACKUP_RESTORE_FAILED
    }

    fun update(error: UpdateError): HelpEntry = when (error) {
        is UpdateError.Offline, is UpdateError.TokenRejected, is UpdateError.NoRelease, is UpdateError.RateLimited,
        is UpdateError.Http, is UpdateError.WrongDownload,
        -> HelpEntry.UPDATE_CHECK_FAILED
    }
}
