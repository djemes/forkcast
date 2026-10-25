package app.forkcast.ui.legal

import androidx.annotation.StringRes
import app.forkcast.R

/** The Terms of Use and Disclaimer's version (roadmap 2.0, task 4.1). */
object Agreement {
    /**
     * Bump only when the meaning changes (never for typos or translations), and add the new
     * version's note to [changes]: everyone who accepted an older version sees it again with that note.
     */
    const val VERSION = 1

    /** "What changed" for each version after the first. */
    val changes: Map<Int, Int> = emptyMap()

    /** Shown at start-up until this version is accepted on this phone. */
    fun needsAcceptance(acceptedVersion: Int?, current: Int = VERSION): Boolean = acceptedVersion == null || acceptedVersion < current
}

/** A heading and its paragraphs; paragraph lines that start with "• " are bullets. */
class LegalSection(@StringRes val heading: Int, @StringRes val body: List<Int>)

/** A legal text as the app shows it and TERMS.md / PRIVACY.md print it. */
class LegalDocument(@StringRes val title: Int, @StringRes val effective: Int, val sections: List<LegalSection>)

object LegalTexts {
    val terms = LegalDocument(
        R.string.terms_title,
        R.string.terms_effective,
        listOf(
            LegalSection(R.string.terms_short_heading, listOf(R.string.terms_short_body)),
            LegalSection(R.string.terms_what_heading, listOf(R.string.terms_what_body)),
            LegalSection(R.string.terms_not_heading, listOf(R.string.terms_not_body)),
            LegalSection(R.string.terms_yours_heading, listOf(R.string.terms_yours_body)),
            LegalSection(R.string.terms_ai_heading, listOf(R.string.terms_ai_body)),
            LegalSection(R.string.terms_data_heading, listOf(R.string.terms_data_body)),
            LegalSection(R.string.terms_warranty_heading, listOf(R.string.terms_warranty_body)),
            LegalSection(R.string.terms_liability_heading, listOf(R.string.terms_liability_body)),
            LegalSection(R.string.terms_donations_heading, listOf(R.string.terms_donations_body)),
            LegalSection(R.string.terms_changes_heading, listOf(R.string.terms_changes_body)),
            LegalSection(R.string.terms_general_heading, listOf(R.string.terms_general_body)),
        ),
    )

    /**
     * Each Android permission the app asks for, by the policy line that explains it. A test fails
     * when the merged manifest asks for one that isn't here (roadmap 2.0, task 4.4).
     */
    val permissions: Map<Int, List<String>> = mapOf(
        R.string.privacy_permission_camera to listOf("android.permission.CAMERA"),
        R.string.privacy_permission_internet to listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
        R.string.privacy_permission_notifications to listOf("android.permission.POST_NOTIFICATIONS"),
        R.string.privacy_permission_background to listOf(
            "android.permission.FOREGROUND_SERVICE", "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED",
        ),
        R.string.privacy_permission_biometric to listOf("android.permission.USE_BIOMETRIC"),
        R.string.privacy_permission_install to listOf(
            "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
        ),
        R.string.privacy_permission_health to listOf(
            "android.permission.health.READ_WEIGHT", "android.permission.health.READ_BODY_FAT",
            "android.permission.health.READ_HEIGHT", "android.permission.health.READ_STEPS",
            "android.permission.health.READ_ACTIVE_CALORIES_BURNED", "android.permission.health.READ_TOTAL_CALORIES_BURNED",
            "android.permission.health.READ_EXERCISE", "android.permission.health.READ_HEALTH_DATA_HISTORY",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            "android.permission.health.WRITE_NUTRITION", "android.permission.health.WRITE_HYDRATION",
        ),
    )

    val privacy = LegalDocument(
        R.string.privacy_title,
        R.string.privacy_effective,
        listOf(
            LegalSection(R.string.privacy_summary_heading, listOf(R.string.privacy_summary_body)),
            LegalSection(R.string.privacy_stored_heading, listOf(R.string.privacy_stored_body)),
            LegalSection(R.string.privacy_leaves_heading, listOf(R.string.privacy_leaves_body)),
            LegalSection(R.string.privacy_permissions_heading, permissions.keys.toList()),
            LegalSection(R.string.privacy_children_heading, listOf(R.string.privacy_children_body)),
            LegalSection(R.string.privacy_security_heading, listOf(R.string.privacy_security_body)),
            LegalSection(R.string.privacy_choices_heading, listOf(R.string.privacy_choices_body)),
            LegalSection(R.string.privacy_changes_heading, listOf(R.string.privacy_changes_body)),
            LegalSection(R.string.privacy_contact_heading, listOf(R.string.privacy_contact_body)),
        ),
    )
}
