package app.forkcast

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android Auto Backup (SPEC §4.11): keys and their keyset, photos, per-phone state and Health
 * Connect tokens never leave the phone through Google's backup.
 */
class BackupRulesTest {

    private fun excludes(file: String, section: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val root = section?.let { doc.getElementsByTagName(it).item(0) as Element } ?: doc.documentElement
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }.toSet()
    }

    private val required = setOf("file:secrets/", "sharedpref:secrets.xml", "sharedpref:device.xml", "file:health-sync/")

    @Test fun cloudBackupLeavesOutKeysPhotosAndPhoneState() {
        assertThat(excludes("data_extraction_rules.xml", "cloud-backup")).containsAtLeastElementsIn(required + "file:photos/")
        assertThat(excludes("backup_rules.xml")).containsAtLeastElementsIn(required + "file:photos/")
    }

    @Test fun deviceTransferLeavesOutKeysAndPhoneState() {
        assertThat(excludes("data_extraction_rules.xml", "device-transfer")).containsAtLeastElementsIn(required)
    }
}
