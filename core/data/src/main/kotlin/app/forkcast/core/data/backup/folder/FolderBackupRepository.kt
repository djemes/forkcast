package app.forkcast.core.data.backup.folder

import app.forkcast.core.data.backup.BackupCrypto
import app.forkcast.core.data.backup.BackupFile
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.OpenedBackupFile
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.PhotoArchive
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.data.settings.LastBackup
import app.forkcast.core.domain.BackupReminder
import app.forkcast.core.domain.BackupRetention
import app.forkcast.core.model.BackupSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Why a folder backup didn't happen; each has its own message and next step. */
enum class FolderProblem {
    /** No folder chosen on this phone. */
    NO_FOLDER,
    /** The folder was deleted, moved, or Forkcast's access to it was removed: choose it again. */
    FOLDER_LOST,
    /** No backup password is set: backups are always encrypted with it (owner, 2026-10-29). */
    PASSWORD_MISSING,
    /** Writing the file failed (full storage, the folder's app had a problem). */
    WRITE_FAILED,
}

data class FolderStatus(
    val folder: String?,
    val folderName: String?,
    val settings: BackupSettings,
    val last: LastBackup?,
    val lastProblem: FolderProblem?,
    val hasPassword: Boolean,
) {
    val chosen: Boolean get() = folder != null
}

/** "Delete old backups": what would happen, worked out before anything is deleted. Only [Ready] can be carried out. */
sealed interface CleanupPlan {
    /** The newest backup was opened and reads as a current-format backup: the others can go. */
    data class Ready(val keep: FolderFile, val delete: List<FolderFile>) : CleanupPlan
    /** One backup or none: nothing to delete. */
    data class NothingToDelete(val keep: FolderFile?) : CleanupPlan
    /** The newest backup can't be restored by this version as it is; nothing is deleted until a new one is made. */
    data class LatestNotRestorable(val latest: FolderFile, val reason: NotRestorable) : CleanupPlan
    data class Failed(val problem: FolderProblem) : CleanupPlan
}

enum class NotRestorable {
    /** Readable, but an export format older than this version writes. */
    OLD_FORMAT,
    /** Not a backup this version can open (damaged, cut short, or from a newer version). */
    UNREADABLE,
    /** Locked with a password other than this phone's backup password. */
    PASSWORD,
}

/** After a password change: the folder's backups now on the new password, and those that couldn't be updated (by name). */
data class ReencryptResult(val updated: Int, val failed: List<String>, val problem: FolderProblem? = null)

/** What Today says about backups, if anything. */
enum class BackupNotice {
    /** No backup for [BackupReminder.DAYS] days. */
    REMINDER,
}

/** How a cleanup went: [deleted] of the planned ones are gone; [failed] are still there. */
data class CleanupResult(val deleted: Int, val failed: Int, val kept: FolderFile)

/**
 * Backups to a folder the user chose (roadmap 2.0 task 1.4; owner, 2026-10-29): a `.forkcast` file
 * ([BackupFile]) per run, always encrypted with the backup password, then the folder's Forkcast
 * backups thinned out by the retention preset ([BackupRetention]) once the new one reads back
 * cleanly. Never touches files Forkcast didn't name.
 */
@Suppress("TooManyFunctions") // The folder, the password and the cleanup belong together.
interface FolderBackupRepository {
    val status: Flow<FolderStatus>
    /** Today's backup card: the no-backup reminder. */
    val notice: Flow<BackupNotice?>
    suspend fun dismissNotice(notice: BackupNotice)
    /** The picker returned [tree]: keep access to it and use it from now on. */
    suspend fun chooseFolder(tree: String): FolderProblem?
    /** Stops using the folder (backups already there stay). */
    suspend fun forgetFolder()
    /** Writes a backup to the folder now; null when it worked. */
    suspend fun backupNow(): FolderProblem?
    /** A backup the user saved with the file picker counts as the last backup too. */
    suspend fun recordSaved(name: String, sizeBytes: Long?)
    suspend fun updateSettings(transform: (BackupSettings) -> BackupSettings)
    /** Sets the backup password the first time (changing one goes through [changePassword]). */
    suspend fun setPassword(password: String)
    /** The backup password, for showing or copying it after the phone's unlock; null when none is set. */
    suspend fun password(): String?
    /** Re-encrypts the folder's backups with [password], then keeps it as the backup password. */
    suspend fun changePassword(password: String): ReencryptResult
    /** Removes the backup password; automatic backups turn off with it. */
    suspend fun removePassword()
    /** Checks that the newest backup can be restored and lists the older ones; deletes nothing. */
    suspend fun cleanupPlan(): CleanupPlan
    /** Deletes [plan]'s older backups, and only those still in the folder. */
    suspend fun deleteOld(plan: CleanupPlan.Ready): CleanupResult
}

@Singleton
class DefaultFolderBackupRepository @Inject constructor(
    private val folder: BackupFolder,
    private val file: BackupFile,
    private val backups: BackupRepository,
    private val archive: PhotoArchive,
    private val device: DevicePrefs,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val clock: Clock,
) : FolderBackupRepository {

    private val mutex = Mutex()

    override val status: Flow<FolderStatus> = combine(device.state, settings.settings, secrets.present) { d, s, present ->
        FolderStatus(
            folder = d.backupFolder,
            folderName = d.backupFolderName,
            settings = s.backup,
            last = d.lastBackup,
            lastProblem = d.lastBackupError?.let { runCatching { FolderProblem.valueOf(it) }.getOrNull() },
            hasPassword = SecretName.BACKUP_PASSWORD in present,
        )
    }

    override val notice: Flow<BackupNotice?> = combine(device.state, settings.settings) { d, s ->
        when {
            s.backup.reminder && BackupReminder.due(
                lastBackup = d.lastBackup?.at?.let(Instant::ofEpochMilli),
                firstSeen = d.firstSeenAt?.let(Instant::ofEpochMilli),
                dismissedAt = d.backupReminderDismissedAt?.let(Instant::ofEpochMilli),
                now = clock.instant(),
            ) -> BackupNotice.REMINDER
            else -> null
        }
    }

    override suspend fun dismissNotice(notice: BackupNotice) = device.update {
        when (notice) {
            BackupNotice.REMINDER -> it.copy(backupReminderDismissedAt = clock.millis())
        }
    }

    override suspend fun chooseFolder(tree: String): FolderProblem? = withContext(Dispatchers.IO) {
        val old = device.state.value.backupFolder
        try {
            folder.keep(tree)
        } catch (e: SecurityException) {
            return@withContext FolderProblem.FOLDER_LOST
        }
        if (old != null && old != tree) folder.release(old)
        device.update { it.copy(backupFolder = tree, backupFolderName = folder.displayName(tree), lastBackupError = null, backupFailures = 0) }
        null
    }

    override suspend fun forgetFolder() = withContext(Dispatchers.IO) {
        device.state.value.backupFolder?.let(folder::release)
        device.update { it.copy(backupFolder = null, backupFolderName = null, lastBackupError = null, backupFailures = 0) }
    }

    override suspend fun backupNow(): FolderProblem? = mutex.withLock {
        val problem = withContext(Dispatchers.IO) { write() }
        device.update {
            if (problem == null) it.copy(lastBackupError = null, backupFailures = 0)
            else it.copy(lastBackupError = problem.name, backupFailures = it.backupFailures + 1)
        }
        problem
    }

    private suspend fun write(): FolderProblem? {
        val tree = device.state.value.backupFolder ?: return FolderProblem.NO_FOLDER
        val password = secrets.get(SecretName.BACKUP_PASSWORD) ?: return FolderProblem.PASSWORD_MISSING
        if (!folder.reachable(tree)) return FolderProblem.FOLDER_LOST
        val s = settings.settings.first().backup
        val created = try {
            folder.create(tree, file.fileName(), BackupFile.MIME_TYPE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // SecurityException, IOException, or a provider's own failure
            return FolderProblem.FOLDER_LOST.takeIf { !folder.reachable(tree) } ?: FolderProblem.WRITE_FAILED
        }
        val size = try {
            folder.openOutput(created.uri).let { out -> file.write(out, password.toCharArray()) }
        } catch (e: CancellationException) {
            folder.delete(created.uri)
            throw e
        } catch (e: Exception) {
            // Never leave half a backup behind: it would look like the newest one.
            folder.delete(created.uri)
            return FolderProblem.WRITE_FAILED
        }
        device.update { it.copy(lastBackup = LastBackup(clock.millis(), created.name, it.backupFolderName, size)) }
        // Old backups go only once the new one opens and reads as a current backup.
        if (restorable(created) == null) prune(tree, keepAlso = created.uri, rule = BackupRetention.rule(s))
        return null
    }

    override suspend fun recordSaved(name: String, sizeBytes: Long?) =
        device.update { it.copy(lastBackup = LastBackup(clock.millis(), name, null, sizeBytes)) }

    override suspend fun updateSettings(transform: (BackupSettings) -> BackupSettings) =
        settings.update { it.copy(backup = transform(it.backup)) }

    override suspend fun setPassword(password: String) = secrets.set(SecretName.BACKUP_PASSWORD, password)

    override suspend fun password(): String? = secrets.get(SecretName.BACKUP_PASSWORD)

    override suspend fun removePassword() {
        secrets.set(SecretName.BACKUP_PASSWORD, null)
        settings.update { it.copy(backup = it.backup.copy(auto = false)) }
    }

    override suspend fun changePassword(password: String): ReencryptResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val old = secrets.get(SecretName.BACKUP_PASSWORD)
            val tree = device.state.value.backupFolder
            val result = when {
                old == null || tree == null -> ReencryptResult(0, emptyList())
                !folder.reachable(tree) -> ReencryptResult(0, emptyList(), FolderProblem.FOLDER_LOST)
                else -> reencryptAll(tree, old.toCharArray(), password.toCharArray())
            }
            // Backups that couldn't be updated keep the old password; new ones use the new password from now on.
            if (result.problem == null) secrets.set(SecretName.BACKUP_PASSWORD, password)
            result
        }
    }

    private fun reencryptAll(tree: String, old: CharArray, new: CharArray): ReencryptResult {
        val files = try {
            newestFirst(tree).filterNot { BackupFile.isUnencryptedName(it.name) }
        } catch (e: Exception) {
            return ReencryptResult(0, emptyList(), FolderProblem.FOLDER_LOST)
        }
        var updated = 0
        val failed = mutableListOf<String>()
        for (f in files) if (reencrypt(tree, f, old, new)) updated++ else failed += f.name
        return ReencryptResult(updated, failed)
    }

    /**
     * Writes [f] again under [new] next to it, checks the copy reads to the end, then replaces the
     * original (the copy takes its name if the folder's app can rename). The zip inside is copied
     * as it is, so nothing is unpacked on the way.
     */
    private fun reencrypt(tree: String, f: FolderFile, old: CharArray, new: CharArray): Boolean {
        val head = runCatching { folder.openInput(f.uri).use { it.readNBytesCompat(BackupCrypto.MAGIC.size) } }.getOrNull() ?: return false
        if (!BackupCrypto.isEncrypted(head)) return false
        val copy = runCatching { folder.create(tree, f.name, BackupFile.MIME_TYPE) }.getOrNull() ?: return false
        val ok = runCatching {
            folder.openInput(f.uri).use { input ->
                BackupCrypto.encrypt(folder.openOutput(copy.uri), new, file.kdf).use { out -> BackupCrypto.decrypt(input, old).copyTo(out) }
            }
            folder.openInput(copy.uri).use { BackupCrypto.decrypt(it, new).drain() }
        }.isSuccess
        if (!ok || !folder.delete(f.uri)) {
            folder.delete(copy.uri)
            return false
        }
        if (copy.name != f.name) folder.rename(copy.uri, f.name)
        return true
    }

    override suspend fun cleanupPlan(): CleanupPlan = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tree = device.state.value.backupFolder ?: return@withContext CleanupPlan.Failed(FolderProblem.NO_FOLDER)
            if (!folder.reachable(tree)) return@withContext CleanupPlan.Failed(FolderProblem.FOLDER_LOST)
            val all = try {
                newestFirst(tree)
            } catch (e: Exception) {
                return@withContext CleanupPlan.Failed(FolderProblem.FOLDER_LOST)
            }
            val latest = all.firstOrNull() ?: return@withContext CleanupPlan.NothingToDelete(null)
            when (val why = restorable(latest)) {
                null -> if (all.size == 1) CleanupPlan.NothingToDelete(latest) else CleanupPlan.Ready(latest, all.drop(1))
                else -> CleanupPlan.LatestNotRestorable(latest, why)
            }
        }
    }

    override suspend fun deleteOld(plan: CleanupPlan.Ready): CleanupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tree = device.state.value.backupFolder
            // Only Forkcast backups still in the folder, listed again now; never the one kept.
            val there = tree?.let { runCatching { newestFirst(it) }.getOrNull() }.orEmpty().map { it.uri }.toSet()
            val targets = plan.delete.filter { it.uri in there && it.uri != plan.keep.uri }
            var deleted = 0
            for (f in targets) if (folder.delete(f.uri)) deleted++
            CleanupResult(deleted, targets.size - deleted, plan.keep)
        }
    }

    /** Null when [f] is a backup this version restores as it is; else why not. Stages nothing for good. */
    private suspend fun restorable(f: FolderFile): NotRestorable? {
        val password = secrets.get(SecretName.BACKUP_PASSWORD)?.toCharArray()
        return try {
            when (val opened = folder.openInput(f.uri).use { file.open(it, password) }) {
                OpenedBackupFile.NeedsPassword, OpenedBackupFile.WrongPassword -> NotRestorable.PASSWORD
                is OpenedBackupFile.Ok -> when (val parsed = backups.parse(opened.json)) {
                    is ParsedBackup.Ok -> if (parsed.export.schemaVersion == ForkcastExport.SCHEMA_VERSION) null else NotRestorable.OLD_FORMAT
                    is ParsedBackup.Rejected -> if (parsed.reason == RejectReason.OLD_VERSION) NotRestorable.OLD_FORMAT else NotRestorable.UNREADABLE
                }
            }
        } catch (e: IOException) {
            NotRestorable.UNREADABLE
        } finally {
            archive.discardStaged()
        }
    }

    /** Forkcast's backups in [tree], newest first (by the time in the name, else the file time). */
    private fun newestFirst(tree: String): List<FolderFile> =
        folder.list(tree)
            .filter { BackupFile.isBackupName(it.name) }
            .sortedWith(compareByDescending<FolderFile> { createdAt(it) }.thenByDescending { it.modifiedAt ?: 0L })

    /** Deletes the Forkcast backups [rule] doesn't keep; a failed delete waits for the next run. */
    private fun prune(tree: String, keepAlso: String, rule: BackupRetention.Rule) {
        val all = runCatching { newestFirst(tree) }.getOrNull() ?: return
        val dated = all.map { BackupRetention.Backup(it.uri, createdAt(it)) }
        val drop = BackupRetention.toDelete(dated, clock.zone, rule)
        all.filter { it.uri in drop && it.uri != keepAlso }.forEach { folder.delete(it.uri) }
    }

    /** When [f] was made: from its name, else the folder's file time. */
    private fun createdAt(f: FolderFile): Instant =
        BackupFile.createdAt(f.name, clock.zone) ?: f.modifiedAt?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
}

/** Reads to the end (a decrypting stream checks every segment on the way). */
private fun InputStream.drain() {
    val buf = ByteArray(64 * 1024)
    while (read(buf) >= 0) Unit
}

/** Up to [n] bytes from the start (InputStream.readNBytes needs API 33). */
private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var total = 0
    while (total < n) {
        val r = read(buf, total, n - total)
        if (r < 0) break
        total += r
    }
    return buf.copyOf(total)
}
