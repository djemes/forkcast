package app.forkcast.ui.help

import androidx.annotation.StringRes
import app.forkcast.R

/** Help's groups, in the order the page lists them (roadmap 2.0 task 3.4). */
enum class HelpTopic(@StringRes val title: Int) {
    GETTING_STARTED(R.string.help_topic_start),
    LOGGING(R.string.help_topic_logging),
    BUDGET(R.string.help_topic_budget),
    AI(R.string.help_topic_ai),
    HEALTH_CONNECT(R.string.help_topic_health),
    BACKUP(R.string.help_topic_backup),
    UPDATES(R.string.help_topic_updates),
    PRIVACY(R.string.help_topic_privacy),
    GLOSSARY(R.string.help_topic_glossary),
}

/**
 * One question and its answer. The names are stable ids: error messages link to them and
 * [app.forkcast.navigation.HelpDestination] carries them, so rename an entry only together with
 * every link (HelpLinksTest catches an error without one).
 */
enum class HelpEntry(val topic: HelpTopic, @StringRes val question: Int, @StringRes val answer: Int) {
    START_FIRST_STEPS(HelpTopic.GETTING_STARTED, R.string.help_q_start, R.string.help_a_start),
    START_WITHOUT_AI(HelpTopic.GETTING_STARTED, R.string.help_q_without_ai, R.string.help_a_without_ai),
    START_CHANGE_GOAL(HelpTopic.GETTING_STARTED, R.string.help_q_change_goal, R.string.help_a_change_goal),

    LOG_WAYS(HelpTopic.LOGGING, R.string.help_q_log_ways, R.string.help_a_log_ways),
    LOG_PHOTO_TIPS(HelpTopic.LOGGING, R.string.help_q_photo_tips, R.string.help_a_photo_tips),
    LOG_SEARCH_FAILED(HelpTopic.LOGGING, R.string.help_q_search_failed, R.string.help_a_search_failed),
    LOG_USDA_KEY(HelpTopic.LOGGING, R.string.help_q_usda_key, R.string.help_a_usda_key),
    LOG_BARCODE(HelpTopic.LOGGING, R.string.help_q_barcode, R.string.help_a_barcode),
    LOG_LABEL(HelpTopic.LOGGING, R.string.help_q_label, R.string.help_a_label),
    LOG_CAMERA(HelpTopic.LOGGING, R.string.help_q_camera, R.string.help_a_camera),

    BUDGET_HOW(HelpTopic.BUDGET, R.string.help_q_budget_how, R.string.help_a_budget_how),
    BUDGET_ADAPTIVE(HelpTopic.BUDGET, R.string.help_q_budget_adaptive, R.string.help_a_budget_adaptive),
    BUDGET_ACTIVITY(HelpTopic.BUDGET, R.string.help_q_budget_activity, R.string.help_a_budget_activity),
    BUDGET_WARNINGS(HelpTopic.BUDGET, R.string.help_q_budget_warnings, R.string.help_a_budget_warnings),

    AI_NOT_SET_UP(HelpTopic.AI, R.string.help_q_ai_not_set_up, R.string.help_a_ai_not_set_up),
    AI_KEY_REJECTED(HelpTopic.AI, R.string.help_q_ai_key, R.string.help_a_ai_key),
    AI_LIMITS(HelpTopic.AI, R.string.help_q_ai_limits, R.string.help_a_ai_limits),
    AI_MODEL(HelpTopic.AI, R.string.help_q_ai_model, R.string.help_a_ai_model),
    AI_CONNECTION(HelpTopic.AI, R.string.help_q_ai_connection, R.string.help_a_ai_connection),
    AI_ODD_ANSWER(HelpTopic.AI, R.string.help_q_ai_odd, R.string.help_a_ai_odd),
    AI_ACCURACY(HelpTopic.AI, R.string.help_q_ai_accuracy, R.string.help_a_ai_accuracy),
    AI_COST(HelpTopic.AI, R.string.help_q_ai_cost, R.string.help_a_ai_cost),

    HC_NOT_SYNCING(HelpTopic.HEALTH_CONNECT, R.string.help_q_hc_sync, R.string.help_a_hc_sync),
    HC_PERMISSIONS(HelpTopic.HEALTH_CONNECT, R.string.help_q_hc_permissions, R.string.help_a_hc_permissions),
    HC_UNAVAILABLE(HelpTopic.HEALTH_CONNECT, R.string.help_q_hc_unavailable, R.string.help_a_hc_unavailable),

    BACKUP_HOW(HelpTopic.BACKUP, R.string.help_q_backup_how, R.string.help_a_backup_how),
    BACKUP_NEW_PHONE(HelpTopic.BACKUP, R.string.help_q_backup_phone, R.string.help_a_backup_phone),
    BACKUP_RESTORE_FAILED(HelpTopic.BACKUP, R.string.help_q_backup_restore, R.string.help_a_backup_restore),
    BACKUP_PASSWORD(HelpTopic.BACKUP, R.string.help_q_backup_password, R.string.help_a_backup_password),
    BACKUP_AUTO_FAILED(HelpTopic.BACKUP, R.string.help_q_backup_auto, R.string.help_a_backup_auto),
    BACKUP_SAVE_FAILED(HelpTopic.BACKUP, R.string.help_q_backup_save, R.string.help_a_backup_save),

    UPDATE_HOW(HelpTopic.UPDATES, R.string.help_q_update_how, R.string.help_a_update_how),
    UPDATE_CHECK_FAILED(HelpTopic.UPDATES, R.string.help_q_update_check, R.string.help_a_update_check),
    UPDATE_INSTALL_FAILED(HelpTopic.UPDATES, R.string.help_q_update_install, R.string.help_a_update_install),

    PRIVACY_DATA(HelpTopic.PRIVACY, R.string.help_q_privacy_data, R.string.help_a_privacy_data),
    PRIVACY_AI(HelpTopic.PRIVACY, R.string.help_q_privacy_ai, R.string.help_a_privacy_ai),
    PRIVACY_CRASH(HelpTopic.PRIVACY, R.string.help_q_privacy_crash, R.string.help_a_privacy_crash),

    TERM_KCAL(HelpTopic.GLOSSARY, R.string.help_term_kcal, R.string.help_def_kcal),
    TERM_MACROS(HelpTopic.GLOSSARY, R.string.help_term_macros, R.string.help_def_macros),
    TERM_TDEE(HelpTopic.GLOSSARY, R.string.help_term_tdee, R.string.help_def_tdee),
    TERM_DV(HelpTopic.GLOSSARY, R.string.help_term_dv, R.string.help_def_dv),
    ;

    companion object {
        /** The entry a link or saved navigation names, or null for an id this version doesn't have. */
        fun byId(id: String?): HelpEntry? = entries.firstOrNull { it.name == id }
    }
}
