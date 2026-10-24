package app.forkcast.core.data.drive

import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.backup.ParsedBackup
import app.forkcast.core.data.backup.RejectReason
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.domain.BackupRetention
import app.forkcast.core.model.BackupSettings
import app.forkcast.core.network.drive.DriveClient
import app.forkcast.core.network.drive.DriveError
import app.forkcast.core.network.drive.DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Why a Drive action didn't happen; each has its own message and next step. */
enum class DriveProblem {
    /** Drive isn't connected on this phone. */
    NOT_CONNECTED,
    /** Access was removed or expired: connect again (needs the screen). */
    RECONNECT,
    /** Google sign-in for Forkcast isn't set up yet (SETUP Stage 5). */
    NOT_SET_UP,
    OFFLINE,
    QUOTA_FULL,
    BUSY,
    ERROR,
}

class DriveProblemException(val problem: DriveProblem) : Exception(problem.name)

data class DriveStatus(
    val account: String?,
    val settings: BackupSettings,
    val lastBackupAt: Long?,
    val lastProblem: DriveProblem?,
) {
    val connected: Boolean get() = account != null
}

/** One backup in the Drive folder. */
data class DriveBackup(val id: String, val name: String, val createdAt: Instant?, val sizeBytes: Long?)

/**
 * "Delete old backups" (owner, 2026-10-24): what would happen, worked out before anything is
 * deleted. Only [Ready] can be carried out.
 */
sealed interface CleanupPlan {
    /** The newest backup was downloaded and reads as a current-format export: the others can go. */
    data class Ready(val keep: DriveBackup, val delete: List<DriveBackup>) : CleanupPlan
    /** One backup or none: nothing to delete. */
    data class NothingToDelete(val keep: DriveBackup?) : CleanupPlan
    /** The newest backup can't be restored by this version; nothing is deleted until a new one is made. */
    data class LatestNotRestorable(val latest: DriveBackup, val reason: NotRestorable) : CleanupPlan
    data class Failed(val problem: DriveProblem) : CleanupPlan
}

enum class NotRestorable {
    /** Readable, but an export format older than this version writes. */
    OLD_FORMAT,
    /** Not a Forkcast export this version can open (damaged, cut short, or from a newer version). */
    UNREADABLE,
}

/** How a cleanup went: [deleted] of the planned ones are gone; [failed] are still there. */
data class CleanupResult(val deleted: Int, val failed: Int, val kept: DriveBackup)

/**
 * Google Drive backup and restore (SPEC §4.11): a gzipped JSON export (the same file as
 * Backup & data's export, so no keys and no photos) uploaded to "Forkcast Backups", keeping
 * the last 14 plus the first of each of the last 12 months.
 */
interface DriveBackupRepository {
    val status: Flow<DriveStatus>
    /** After the user granted access: remembers the account and finds or creates the folder. */
    suspend fun connect(token: String): DriveProblem?
    /** Uploads a backup now; null when it worked. [interactive] = the user tapped "Back up now". */
    suspend fun backupNow(interactive: Boolean = false): DriveProblem?
    suspend fun backups(): List<DriveBackup>
    /** The backup's JSON, ready for BackupRepository.parse. */
    suspend fun download(id: String): String
    suspend fun disconnect()
    suspend fun updateSettings(transform: (BackupSettings) -> BackupSettings)
    /** Checks that the newest backup can be restored and lists the older ones; deletes nothing. */
    suspend fun cleanupPlan(): CleanupPlan
    /** Deletes [plan]'s older backups, and only those still in the Forkcast Backups folder. */
    suspend fun deleteOld(plan: CleanupPlan.Ready): Result<CleanupResult>
}

@Singleton
class DefaultDriveBackupRepository @Inject constructor(
    private val auth: DriveAuth,
    private val drive: DriveClient,
    private val backup: BackupRepository,
    private val device: DevicePrefs,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : DriveBackupRepository {

    private val mutex = Mutex()

    override val status: Flow<DriveStatus> = combine(device.state, settings.settings) { d, s ->
        DriveStatus(d.driveAccount, s.backup, d.lastDriveBackupAt, d.lastDriveError?.let { runCatching { DriveProblem.valueOf(it) }.getOrNull() })
    }

    override suspend fun connect(token: String): DriveProblem? = attempt {
        val email = drive.accountEmail(token)
        val folder = drive.folder(token)
        device.update { it.copy(driveAccount = email ?: it.driveAccount ?: UNKNOWN_ACCOUNT, driveFolderId = folder, lastDriveError = null) }
    }

    override suspend fun backupNow(interactive: Boolean): DriveProblem? = mutex.withLock {
        if (device.state.value.driveAccount == null) return DriveProblem.NOT_CONNECTED
        val problem = attempt {
            withToken { token ->
                val bytes = gzip(backup.exportJson())
                val name = "forkcast-" + STAMP.format(clock.instant().atZone(clock.zone)) + ".json.gz"
                val props = mapOf(PROP_KIND to PROP_BACKUP, PROP_SCHEMA to ForkcastExport.SCHEMA_VERSION.toString())
                val folder = folderId(token)
                try {
                    drive.upload(token, folder, name, bytes, props)
                } catch (e: DriveError.Http) {
                    // The folder was deleted in Drive: make a new one and try once more.
                    if (e.code != 404) throw e
                    drive.upload(token, folderId(token, refresh = true), name, bytes, props)
                }
                prune(token)
            }
        }
        device.update {
            if (problem == null) it.copy(lastDriveBackupAt = clock.millis(), lastDriveError = null) else it.copy(lastDriveError = problem.name)
        }
        problem
    }

    override suspend fun backups(): List<DriveBackup> {
        var out: List<DriveBackup> = emptyList()
        attempt { withToken { token -> out = list(token).map { it.toBackup() } } }?.let { throw DriveProblemException(it) }
        return out
    }

    override suspend fun download(id: String): String {
        var bytes = ByteArray(0)
        attempt { withToken { token -> bytes = drive.download(token, id) } }?.let { throw DriveProblemException(it) }
        return unzip(bytes)
    }

    private suspend fun unzip(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        // Backups are gzipped; a plain .json someone put in the folder still opens.
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() }
        } else {
            bytes.decodeToString()
        }
    }

    override suspend fun disconnect() {
        device.state.value.driveAccount?.takeIf { it != UNKNOWN_ACCOUNT }?.let { auth.revoke(it) }
        device.update { it.copy(driveAccount = null, driveFolderId = null, lastDriveError = null) }
    }

    override suspend fun updateSettings(transform: (BackupSettings) -> BackupSettings) =
        settings.update { it.copy(backup = transform(it.backup)) }

    override suspend fun cleanupPlan(): CleanupPlan = mutex.withLock {
        if (device.state.value.driveAccount == null) return CleanupPlan.Failed(DriveProblem.NOT_CONNECTED)
        var plan: CleanupPlan = CleanupPlan.NothingToDelete(null)
        val problem = attempt {
            withToken { token ->
                val all = list(token).map { it.toBackup() }.sortedWith(compareByDescending<DriveBackup> { it.createdAt }.thenByDescending { it.name })
                val latest = all.firstOrNull()
                plan = when {
                    latest == null -> CleanupPlan.NothingToDelete(null)
                    else -> when (val why = restorable(drive.download(token, latest.id))) {
                        null -> if (all.size == 1) CleanupPlan.NothingToDelete(latest) else CleanupPlan.Ready(latest, all.drop(1))
                        else -> CleanupPlan.LatestNotRestorable(latest, why)
                    }
                }
            }
        }
        problem?.let { CleanupPlan.Failed(it) } ?: plan
    }

    override suspend fun deleteOld(plan: CleanupPlan.Ready): Result<CleanupResult> = mutex.withLock {
        var deleted = 0
        var failed = 0
        val problem = attempt {
            withToken { token ->
                // Only files Forkcast made in its own folder, listed again now; never the one kept.
                val inFolder = list(token).map { it.id }.toSet()
                val targets = plan.delete.filter { it.id in inFolder && it.id != plan.keep.id }
                deleted = 0
                failed = 0
                for (b in targets) {
                    if (runCatching { drive.delete(token, b.id) }.isSuccess) deleted++ else failed++
                }
            }
        }
        if (problem != null && deleted == 0) Result.failure(DriveProblemException(problem))
        else Result.success(CleanupResult(deleted, failed, plan.keep))
    }

    /** Null when [bytes] is a backup this version restores as it is; else why not. */
    private suspend fun restorable(bytes: ByteArray): NotRestorable? {
        val text = runCatching { unzip(bytes) }.getOrNull() ?: return NotRestorable.UNREADABLE
        return when (val parsed = backup.parse(text)) {
            is ParsedBackup.Ok -> if (parsed.export.schemaVersion == ForkcastExport.SCHEMA_VERSION) null else NotRestorable.OLD_FORMAT
            is ParsedBackup.Rejected -> if (parsed.reason == RejectReason.OLD_VERSION) NotRestorable.OLD_FORMAT else NotRestorable.UNREADABLE
        }
    }

    private suspend fun list(token: String): List<DriveFile> =
        drive.list(token, folderId(token)).filter { it.name.startsWith("forkcast-") && (it.name.endsWith(".json.gz") || it.name.endsWith(".json")) }

    /** Deletes what the retention rule doesn't keep; a failed delete waits for the next run. */
    private suspend fun prune(token: String) {
        val files = list(token)
        val keep = files.mapNotNull { f -> f.createdAt()?.let { BackupRetention.Backup(f.id, it) } }
        BackupRetention.toDelete(keep, clock.zone).forEach { id -> runCatching { drive.delete(token, id) } }
    }

    private suspend fun folderId(token: String, refresh: Boolean = false): String {
        val known = device.state.value.driveFolderId.takeIf { !refresh }
        if (known != null) return known
        val id = drive.folder(token)
        device.update { it.copy(driveFolderId = id) }
        return id
    }

    /** Runs [block] with a fresh token; a refused token is dropped and the block tried once more. */
    private suspend fun withToken(block: suspend (String) -> Unit) {
        val token = grant()
        try {
            block(token)
        } catch (e: DriveError.Unauthorized) {
            auth.forget(token)
            block(grant())
        }
    }

    private suspend fun grant(): String = when (val a = auth.authorize()) {
        is DriveAccess.Granted -> a.token
        is DriveAccess.NeedsConsent -> throw DriveProblemException(DriveProblem.RECONNECT)
        is DriveAccess.Failed -> throw DriveProblemException(
            when (a.reason) {
                DriveAccess.NOT_SET_UP -> DriveProblem.NOT_SET_UP
                DriveAccess.OFFLINE -> DriveProblem.OFFLINE
                else -> DriveProblem.ERROR
            },
        )
    }

    /** Runs [block] and names what went wrong instead of throwing. */
    private suspend fun attempt(block: suspend () -> Unit): DriveProblem? = try {
        block()
        null
    } catch (e: DriveProblemException) {
        e.problem
    } catch (e: DriveError) {
        when (e) {
            is DriveError.Offline -> DriveProblem.OFFLINE
            is DriveError.Unauthorized -> DriveProblem.RECONNECT
            is DriveError.QuotaFull -> DriveProblem.QUOTA_FULL
            is DriveError.RateLimited -> DriveProblem.BUSY
            is DriveError.Http -> if (e.code >= 500) DriveProblem.BUSY else DriveProblem.ERROR
        }
    } catch (e: kotlinx.serialization.SerializationException) {
        DriveProblem.ERROR
    } catch (e: io.ktor.serialization.JsonConvertException) {
        DriveProblem.ERROR
    }

    private suspend fun gzip(text: String): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
    }

    private fun DriveFile.createdAt(): Instant? = createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun DriveFile.toBackup() = DriveBackup(id, name, createdAt(), size?.toLongOrNull())

    companion object {
        /** Drive didn't say which account (shown as "Google Drive"). */
        const val UNKNOWN_ACCOUNT = "?"
        private const val PROP_KIND = "forkcast"
        private const val PROP_BACKUP = "backup"
        private const val PROP_SCHEMA = "schemaVersion"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
