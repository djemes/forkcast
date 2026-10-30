package app.forkcast.core.data.backup

import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The saved keys inside a backup (owner, 2026-10-29: backups carry everything, API keys
 * included): the AI services' keys and the USDA key. Never the backup password itself (it opens
 * the file).
 */
@Singleton
class BackupSecrets @Inject constructor(private val store: SecretStore) {

    suspend fun forBackup(): Map<String, String> = store.exportAll().filterKeys { it !in perPhone }

    /**
     * After a restore: [REPLACE][ImportMode.REPLACE] makes the file's keys this phone's (keeping
     * the backup password); [MERGE][ImportMode.MERGE] adds only keys this phone
     * doesn't have.
     */
    suspend fun restore(keys: Map<String, String>, mode: ImportMode) {
        val incoming = keys.filterKeys { it !in perPhone }
        if (incoming.isEmpty() && mode == ImportMode.MERGE) return
        val local = store.exportAll()
        val result = when (mode) {
            ImportMode.REPLACE -> local.filterKeys { it in perPhone } + incoming
            ImportMode.MERGE -> incoming + local
        }
        if (result != local) store.replaceAll(result)
    }

    companion object {
        // The legacy updates token (1.5–1.8) never left the phone either; SecretStore.refresh() deletes it.
        private val perPhone = setOf(SecretName.BACKUP_PASSWORD.name, SecretStore.LEGACY_GITHUB_TOKEN)
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        fun encode(keys: Map<String, String>): String = BackupJson.encodeToString(serializer, keys)

        /** An unreadable or missing `secrets.json` restores no keys rather than failing the restore. */
        fun decode(text: String?): Map<String, String> =
            text?.let { runCatching { BackupJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }
}
