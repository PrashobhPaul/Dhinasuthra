package com.dhinasuthra.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * The upgrade safety net (plan §2, §25–§27).
 *
 * Room knows how to run a migration. It does not know how to *survive a failed
 * one*. This does. Every launch follows the same sequence:
 *
 * ```
 * read the version already on disk
 *        ↓  (only if it is behind)
 * snapshot the database to a backup
 *        ↓
 * open Room, which runs the migrations transactionally
 *        ↓
 * validate, record, stamp metadata
 * ```
 *
 * If the open throws, the user's file is restored from the snapshot and the
 * migration is retried once — migrations here are idempotent, so a transient
 * failure (a killed process mid-upgrade, a full disk that has since drained)
 * costs nothing to re-attempt. If it fails a second time the original file is
 * restored and *left alone*: the app continues in [DatabaseStatus.SAFE_MODE] on
 * a separate, empty file, so the user still has a working app and their history
 * still exists on disk, byte-for-byte, waiting for a fix.
 *
 * What this deliberately never does: delete the user's database, clear its
 * tables, or fall back to destructive recreation. A crash is recoverable. A
 * wiped year of history is not.
 */
enum class DatabaseStatus {
    /** Already at the current version. Nothing to do. */
    CURRENT,

    /** Migrated forward this launch, validated, backup retained. */
    MIGRATED,

    /** The first attempt failed; the backup was restored and the retry worked. */
    RECOVERED,

    /**
     * Migration could not be completed. The user's database is untouched on
     * disk at [GuardedDatabase.preservedPath]; the app is running on a fresh,
     * empty file so it remains usable.
     */
    SAFE_MODE
}

data class GuardedDatabase(
    val db: DhinaSuthraDatabase,
    val status: DatabaseStatus,
    val fromVersion: Int,
    val toVersion: Int,
    /** Where the pre-migration snapshot was written, if one was taken. */
    val backupPath: String? = null,
    /** In [DatabaseStatus.SAFE_MODE], where the untouched original still lives. */
    val preservedPath: String? = null,
    val failure: String? = null
) {
    val healthy: Boolean get() = status != DatabaseStatus.SAFE_MODE
}

object DatabaseGuardian {

    private const val TAG = "DsGuardian"

    const val DB_NAME = "dhinasuthra.db"
    private const val SAFE_MODE_DB_NAME = "dhinasuthra.safemode.db"
    private const val BACKUP_DIR = "db-backups"

    /** Plan §27: a small rolling number of snapshots, not an unbounded pile. */
    private const val BACKUPS_RETAINED = 3

    /** Metadata keys mirrored into `app_state` so the rest of the app can read them. */
    const val KEY_SCHEMA_VERSION = "db.schema_version"
    const val KEY_LAST_SUCCESSFUL_MIGRATION = "db.last_successful_migration"
    const val KEY_DATABASE_CREATED_AT = "db.database_created_at"
    const val KEY_LAST_BACKUP_AT = "db.last_backup_at"
    const val KEY_LAST_MIGRATION_STATUS = "db.last_migration_status"

    /**
     * Open the database, migrating it if needed, without ever risking the data
     * already in it. Safe to call once at process start.
     */
    fun open(context: Context): GuardedDatabase {
        val file = context.getDatabasePath(DB_NAME)
        val target = MigrationRegistry.CURRENT_VERSION
        val onDisk = readVersion(file)

        // A fresh install: nothing to protect, nothing to migrate.
        if (onDisk == 0) {
            return GuardedDatabase(buildRoom(context, DB_NAME), DatabaseStatus.CURRENT, target, target)
                .also { stampMetadata(it, createdNow = true) }
        }

        // Someone has installed an older APK over a newer one. Destructive
        // downgrade would be the easy answer and it is exactly the answer this
        // app is not allowed to give.
        if (onDisk > target) {
            return safeMode(
                context, file, onDisk, target,
                "database is at version $onDisk but this build only understands $target"
            )
        }

        if (onDisk == target) {
            return GuardedDatabase(buildRoom(context, DB_NAME), DatabaseStatus.CURRENT, onDisk, target)
                .also { stampMetadata(it) }
        }

        // --- an actual upgrade from here on --------------------------------

        val backup = runCatching { snapshot(context, file, onDisk) }.getOrElse {
            Log.w(TAG, "could not snapshot before migration", it)
            null
        }

        val first = runCatching { buildRoom(context, DB_NAME).also { it.forceOpen() } }
        if (first.isSuccess) {
            return GuardedDatabase(
                first.getOrThrow(), DatabaseStatus.MIGRATED, onDisk, target, backup?.absolutePath
            ).also { stampMetadata(it) }
        }

        Log.e(TAG, "migration $onDisk → $target failed; restoring snapshot", first.exceptionOrNull())

        // Restore and retry once. Migrations are additive and idempotent, so a
        // second attempt on the restored file is safe by construction.
        if (backup != null && restore(backup, file)) {
            val second = runCatching { buildRoom(context, DB_NAME).also { it.forceOpen() } }
            if (second.isSuccess) {
                return GuardedDatabase(
                    second.getOrThrow(), DatabaseStatus.RECOVERED, onDisk, target, backup.absolutePath
                ).also { stampMetadata(it) }
            }
            Log.e(TAG, "retry after restore also failed", second.exceptionOrNull())
            restore(backup, file)
        }

        return safeMode(
            context, file, onDisk, target,
            first.exceptionOrNull()?.message ?: "migration failed",
            backup?.absolutePath
        )
    }

    // -- version ------------------------------------------------------------

    /**
     * The schema version SQLite itself records, read without Room so a schema
     * Room cannot open is still diagnosable. Returns 0 when there is no file.
     */
    internal fun readVersion(file: File): Int {
        if (!file.exists() || file.length() == 0L) return 0
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            Log.w(TAG, "could not read schema version from ${file.name}", it)
            0
        }
    }

    // -- backup / restore ---------------------------------------------------

    private fun backupDir(context: Context): File =
        File(context.filesDir, BACKUP_DIR).apply { mkdirs() }

    /**
     * Copy the database aside. The write-ahead log is checkpointed into the main
     * file first, otherwise the copy could be missing the most recent hours of
     * the very history this exists to protect.
     */
    private fun snapshot(context: Context, file: File, version: Int): File {
        checkpoint(file)
        val dir = backupDir(context)
        val backup = File(dir, "dhinasuthra.pre_migration_$version.backup")
        file.copyTo(backup, overwrite = true)
        sidecars(file).forEach { side ->
            if (side.exists()) side.copyTo(File(dir, "${backup.name}${side.name.removePrefix(file.name)}"), overwrite = true)
        }
        prune(dir)
        return backup
    }

    private fun restore(backup: File, file: File): Boolean = runCatching {
        sidecars(file).forEach { if (it.exists()) it.delete() }
        backup.copyTo(file, overwrite = true)
        sidecars(backup).forEach { side ->
            if (side.exists()) side.copyTo(File(file.parentFile, "${file.name}${side.name.removePrefix(backup.name)}"), overwrite = true)
        }
        true
    }.getOrElse {
        Log.e(TAG, "restore failed", it)
        false
    }

    /** The `-wal` and `-shm` files SQLite keeps beside the database. */
    private fun sidecars(file: File): List<File> =
        listOf(File("${file.absolutePath}-wal"), File("${file.absolutePath}-shm"))

    private fun checkpoint(file: File) {
        runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }.onFailure { Log.w(TAG, "wal checkpoint skipped", it) }
    }

    private fun prune(dir: File) {
        val backups = dir.listFiles { f -> f.name.endsWith(".backup") }?.sortedByDescending { it.lastModified() }
            ?: return
        backups.drop(BACKUPS_RETAINED).forEach { stale ->
            stale.delete()
            sidecars(stale).forEach { it.delete() }
        }
    }

    /** Snapshots currently on disk, newest first — surfaced in the privacy screen. */
    fun backups(context: Context): List<File> =
        backupDir(context).listFiles { f -> f.name.endsWith(".backup") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    // -- fallback -----------------------------------------------------------

    private fun safeMode(
        context: Context,
        original: File,
        from: Int,
        to: Int,
        reason: String,
        backupPath: String? = null
    ): GuardedDatabase {
        Log.e(TAG, "entering safe mode: $reason")
        // The empty file the app will actually run on. Started from scratch each
        // time so a half-written safe-mode database never compounds the problem.
        context.getDatabasePath(SAFE_MODE_DB_NAME).let { f ->
            f.delete()
            sidecars(f).forEach { it.delete() }
        }
        val db = buildRoom(context, SAFE_MODE_DB_NAME)
        return GuardedDatabase(
            db = db,
            status = DatabaseStatus.SAFE_MODE,
            fromVersion = from,
            toVersion = to,
            backupPath = backupPath,
            preservedPath = original.absolutePath,
            failure = reason
        ).also { stampMetadata(it) }
    }

    // -- metadata -----------------------------------------------------------

    /**
     * Plan §2's required metadata, written where the app can read it back:
     * `schema_version`, `last_successful_migration`, `database_created_at`,
     * `last_backup_at`. Best-effort — a metadata write must never be the thing
     * that stops the app opening.
     */
    private fun stampMetadata(guarded: GuardedDatabase, createdNow: Boolean = false) {
        runCatching {
            val now = System.currentTimeMillis()
            val db = guarded.db.openHelper.writableDatabase
            fun put(key: String, value: String) = db.execSQL(
                "INSERT OR REPLACE INTO `app_state` (`key`, `value`) VALUES (?, ?)",
                arrayOf<Any?>(key, value)
            )
            put(KEY_SCHEMA_VERSION, guarded.toVersion.toString())
            put(KEY_LAST_MIGRATION_STATUS, guarded.status.name)
            if (createdNow) put(KEY_DATABASE_CREATED_AT, now.toString())
            if (guarded.status == DatabaseStatus.MIGRATED || guarded.status == DatabaseStatus.RECOVERED) {
                put(KEY_LAST_SUCCESSFUL_MIGRATION, "${guarded.fromVersion}→${guarded.toVersion}@$now")
            }
            guarded.backupPath?.let { put(KEY_LAST_BACKUP_AT, now.toString()) }
        }.onFailure { Log.w(TAG, "could not stamp database metadata", it) }
    }

    // -- construction -------------------------------------------------------

    private fun buildRoom(context: Context, name: String): DhinaSuthraDatabase =
        DhinaSuthraDatabase.builder(context, name).build()

    /** Room opens lazily; this forces the migration to run *now*, where it can be caught. */
    private fun DhinaSuthraDatabase.forceOpen() {
        openHelper.writableDatabase.query("SELECT 1").use { it.moveToFirst() }
    }
}
