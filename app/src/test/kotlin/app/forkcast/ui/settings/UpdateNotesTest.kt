package app.forkcast.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateNotesTest {
    @Test fun markdownBecomesPlainText() {
        val md = "\n## Backups you control\n- Save a **backup file** anywhere.\n* Restore `.forkcast` files.\nPlain line."
        assertThat(plainNotes(md)).isEqualTo("Backups you control\n• Save a backup file anywhere.\n• Restore .forkcast files.\nPlain line.")
    }
}
