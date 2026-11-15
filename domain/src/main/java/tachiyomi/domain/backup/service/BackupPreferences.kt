package tachiyomi.domain.backup.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BackupPreferences(
    preferenceStore: PreferenceStore,
) {

    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    /** Auto backups older than this many days are deleted when a new one is created; 0 keeps all. */
    val backupRetentionDays: Preference<Int> = preferenceStore.getInt("backup_retention_days", 0)

    val lastAutoBackupTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_auto_backup_timestamp"),
        0L,
    )
}
