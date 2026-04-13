/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors (https://github.com/strato-emu/)
 */

package org.stratoemu.strato.preference

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stratoemu.strato.R
import org.stratoemu.strato.utils.BackupManager
import org.stratoemu.strato.utils.BackupSelection
import androidx.preference.R as AndroidR

class BackupRestorePreference @JvmOverloads constructor(
    context : Context,
    attrs : AttributeSet? = null,
    defStyleAttr : Int = AndroidR.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private val documentPicker : ActivityResultLauncher<Array<String>> = BackupManager.registerDocumentPicker(context) {
        showRestoreSuccessDialog()
    }

    // La sélection est conservée entre le dialog checklist et le callback du file picker
    private var pendingSelection : BackupSelection? = null

    private val createDocumentLauncher : ActivityResultLauncher<String> = BackupManager.registerCreateDocument(context) { uri ->
        pendingSelection?.let { selection ->
            BackupManager.exportBackupToUri(context, selection, uri)
            pendingSelection = null
        }
    }

    override fun onClick() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.backup_restore_desc)
            .setPositiveButton(R.string.backup_export) { _, _ -> showExportChecklist() }
            .setNegativeButton(R.string.backup_import) { _, _ -> BackupManager.importBackup(documentPicker) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showExportChecklist() {
        val labels = arrayOf(
            context.getString(R.string.backup_global_settings),
            context.getString(R.string.backup_osc_settings),
            context.getString(R.string.backup_input_bindings),
            context.getString(R.string.backup_per_game_settings),
            context.getString(R.string.backup_game_saves),
            context.getString(R.string.backup_gpu_drivers)
        )
        val checked = booleanArrayOf(true, true, true, true, true, true)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_select_content)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.backup_export) { _, _ ->
                val selection = BackupSelection(
                    globalSettings = checked[0],
                    oscSettings = checked[1],
                    inputBindings = checked[2],
                    perGameSettings = checked[3],
                    gameSaves = checked[4],
                    gpuDrivers = checked[5]
                )
                if (!selection.isEmpty()) {
                    pendingSelection = selection
                    createDocumentLauncher.launch(BackupManager.suggestedFileName())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Shown after a successful restore. Dismissing the dialog closes SettingsActivity so the user
     * lands back on MainActivity, which will rescan the game list thanks to refresh_required=true.
     */
    private fun showRestoreSuccessDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_restored_ok)
            .setMessage(R.string.backup_restored_desc)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (context as? Activity)?.finish()
            }
            .setCancelable(false)
            .show()
    }
}
