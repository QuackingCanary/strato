/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors (https://github.com/strato-emu/)
 */

package org.stratoemu.strato.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.stratoemu.strato.BuildConfig
import org.stratoemu.strato.R
import org.stratoemu.strato.StratoApplication
import org.stratoemu.strato.getPublicFilesDir
import org.stratoemu.strato.input.AxisGuestEvent
import org.stratoemu.strato.input.AxisId
import org.stratoemu.strato.input.ButtonGuestEvent
import org.stratoemu.strato.input.ButtonId
import org.stratoemu.strato.input.Controller
import org.stratoemu.strato.input.ControllerType
import org.stratoemu.strato.input.GuestEvent
import org.stratoemu.strato.input.HostEvent
import org.stratoemu.strato.input.JoyConLeftController
import org.stratoemu.strato.input.JoyConRightController
import org.stratoemu.strato.input.KeyHostEvent
import org.stratoemu.strato.input.MotionHostEvent
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BackupSelection(
    val globalSettings : Boolean = true,
    val oscSettings : Boolean = true,
    val inputBindings : Boolean = true,
    val perGameSettings : Boolean = true,
    val gameSaves : Boolean = true,
    val gpuDrivers : Boolean = true
) {
    fun isEmpty() = !globalSettings && !oscSettings && !inputBindings && !perGameSettings && !gameSaves && !gpuDrivers
}

interface BackupManager {
    companion object {
        private const val TAG = "BackupManager"

        private const val MANIFEST_FILE = "manifest.json"
        private const val GLOBAL_PREFS_FILE = "settings/global_prefs.json"
        private const val OSC_PREFS_FILE = "settings/osc_prefs.json"
        private const val INPUT_FILE = "settings/input.json"
        private const val PER_GAME_DIR = "settings/per_game/"
        private const val SAVES_DIR = "saves/"
        private const val DRIVERS_DIR = "drivers/"
        private const val SCHEMA_VERSION = 1

        private val savesFolderRoot
            get() = "${StratoApplication.instance.getPublicFilesDir().canonicalPath}/switch/nand/user/save/0000000000000000/00000000000000000000000000000001"

        fun suggestedFileName() : String {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            return "strato_backup_$timestamp.zip"
        }

        /**
         * Registers an ACTION_CREATE_DOCUMENT launcher. The [onUri] callback is invoked with the
         * destination URI chosen by the user; pass it to [exportBackupToUri].
         */
        fun registerCreateDocument(context : Context, onUri : (Uri) -> Unit) : ActivityResultLauncher<String> {
            return (context as ComponentActivity).registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip")
            ) { uri -> uri?.let { onUri(it) } }
        }

        fun registerDocumentPicker(context : Context, onSuccess : () -> Unit = {}) : ActivityResultLauncher<Array<String>> {
            return (context as ComponentActivity).registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { restoreBackup(context, it, onSuccess) }
            }
        }

        /**
         * Writes the backup ZIP directly to [uri] (the URI returned by ACTION_CREATE_DOCUMENT).
         * No temporary file is created.
         */
        fun exportBackupToUri(context : Context, selection : BackupSelection, uri : Uri) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Cannot open output stream for $uri")
                    outputStream.use { createBackupZip(context, selection, it) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.backup_exported_ok, Toast.LENGTH_LONG).show()
                    }
                } catch (e : Exception) {
                    Log.e(TAG, "Backup export failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        fun importBackup(documentPicker : ActivityResultLauncher<Array<String>>) {
            documentPicker.launch(arrayOf("application/zip"))
        }

        private fun createBackupZip(context : Context, selection : BackupSelection, outputStream : OutputStream) {
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                val sections = buildList {
                    if (selection.globalSettings) add("global_settings")
                    if (selection.oscSettings) add("osc_settings")
                    if (selection.inputBindings) add("input_bindings")
                    if (selection.perGameSettings) add("per_game_settings")
                    if (selection.gameSaves) add("game_saves")
                    if (selection.gpuDrivers) add("gpu_drivers")
                }

                val manifest = JSONObject().apply {
                    put("schema_version", SCHEMA_VERSION)
                    put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("sections", JSONArray(sections))
                }
                zos.putTextEntry(MANIFEST_FILE, manifest.toString(2))

                if (selection.globalSettings) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    zos.putTextEntry(GLOBAL_PREFS_FILE, prefs.toJson().toString(2))
                }

                if (selection.oscSettings) {
                    val prefs = context.getSharedPreferences("controller_config", Context.MODE_PRIVATE)
                    zos.putTextEntry(OSC_PREFS_FILE, prefs.toJson().toString(2))
                }

                if (selection.inputBindings) {
                    readInputBinAsJson(context)?.let { json ->
                        zos.putTextEntry(INPUT_FILE, json.toString(2))
                    }
                }

                if (selection.perGameSettings) {
                    val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                    val pkgPrefix = "${context.packageName}_"
                    val globalPrefsName = "${context.packageName}_preferences"
                    sharedPrefsDir.listFiles()?.filter { file ->
                        file.nameWithoutExtension.startsWith(pkgPrefix) &&
                        file.nameWithoutExtension != globalPrefsName
                    }?.forEach { file ->
                        val titleId = file.nameWithoutExtension.removePrefix(pkgPrefix)
                        val prefs = context.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE)
                        zos.putTextEntry("$PER_GAME_DIR$titleId.json", prefs.toJson().toString(2))
                    }
                }

                if (selection.gameSaves) {
                    val savesFolder = File(savesFolderRoot)
                    if (savesFolder.exists()) {
                        savesFolder.walkTopDown().forEach { file ->
                            val relative = file.absolutePath.removePrefix(savesFolderRoot).removePrefix("/")
                            if (relative.isEmpty()) return@forEach
                            val entry = ZipEntry("$SAVES_DIR$relative${if (file.isDirectory) "/" else ""}")
                            zos.putNextEntry(entry)
                            if (file.isFile) file.inputStream().use { it.copyTo(zos) }
                        }
                    }
                }

                if (selection.gpuDrivers) {
                    val driversDir = File(context.filesDir.canonicalPath, "gpu_drivers")
                    if (driversDir.exists()) {
                        driversDir.walkTopDown().forEach { file ->
                            val relative = file.absolutePath.removePrefix(driversDir.canonicalPath).removePrefix("/")
                            if (relative.isEmpty()) return@forEach
                            val entry = ZipEntry("$DRIVERS_DIR$relative${if (file.isDirectory) "/" else ""}")
                            zos.putNextEntry(entry)
                            if (file.isFile) file.inputStream().use { it.copyTo(zos) }
                        }
                    }
                }
            }
        }

        private fun restoreBackup(context : Context, uri : Uri, onSuccess : () -> Unit = {}) {
            CoroutineScope(Dispatchers.IO).launch {
                val cacheDir = File(context.cacheDir, "backup_restore").apply {
                    deleteRecursively()
                    mkdirs()
                }
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open backup file")
                    ZipUtils.unzip(inputStream, cacheDir)

                    val manifestFile = File(cacheDir, MANIFEST_FILE)
                    if (!manifestFile.exists()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.backup_invalid_file, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val manifest = JSONObject(manifestFile.readText())
                    val sections = manifest.getJSONArray("sections").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }

                    if ("global_settings" in sections) {
                        File(cacheDir, GLOBAL_PREFS_FILE).takeIf { it.exists() }?.let { file ->
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            // Overlay les valeurs du backup sans effacer les clés absentes du backup
                            // (évite de perdre search_location si elle n'était pas encore définie au moment du backup)
                            prefs.edit()
                                .fromJson(JSONObject(file.readText()))
                                // Force un rescan de la liste de jeux au retour sur MainActivity
                                .putBoolean("refresh_required", true)
                                .apply()
                        }
                    }

                    if ("osc_settings" in sections) {
                        File(cacheDir, OSC_PREFS_FILE).takeIf { it.exists() }?.let { file ->
                            val prefs = context.getSharedPreferences("controller_config", Context.MODE_PRIVATE)
                            prefs.edit().fromJson(JSONObject(file.readText())).apply()
                        }
                    }

                    if ("input_bindings" in sections) {
                        File(cacheDir, INPUT_FILE).takeIf { it.exists() }?.let { file ->
                            writeInputBinFromJson(context, JSONObject(file.readText()))
                        }
                    }

                    if ("per_game_settings" in sections) {
                        val perGameDir = File(cacheDir, PER_GAME_DIR)
                        perGameDir.takeIf { it.exists() }?.listFiles()?.forEach { file ->
                            val prefName = "${context.packageName}_${file.nameWithoutExtension}"
                            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                            prefs.edit().fromJson(JSONObject(file.readText())).apply()
                        }
                    }

                    if ("game_saves" in sections) {
                        val savesBackupDir = File(cacheDir, SAVES_DIR)
                        if (savesBackupDir.exists()) {
                            val savesFolder = File(savesFolderRoot).apply { mkdirs() }
                            savesBackupDir.listFiles()?.forEach { gameDir ->
                                val targetDir = File(savesFolder, gameDir.name)
                                targetDir.deleteRecursively()
                                gameDir.copyRecursively(targetDir, overwrite = true)
                            }
                        }
                    }

                    if ("gpu_drivers" in sections) {
                        val driversBackupDir = File(cacheDir, DRIVERS_DIR)
                        if (driversBackupDir.exists()) {
                            val driversDir = File(context.filesDir.canonicalPath, "gpu_drivers").apply { mkdirs() }
                            driversBackupDir.listFiles()?.filter { it.isDirectory }?.forEach { driverDir ->
                                val targetDir = File(driversDir, driverDir.name)
                                // Ne pas écraser un driver déjà installé
                                if (!targetDir.exists()) {
                                    driverDir.copyRecursively(targetDir, overwrite = true)
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) { onSuccess() }
                } catch (e : Exception) {
                    Log.e(TAG, "Backup restore failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    cacheDir.deleteRecursively()
                }
            }
        }

        // ---- SharedPreferences helpers ----

        private fun SharedPreferences.toJson() : JSONObject {
            val json = JSONObject()
            all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    // JSON n'a pas de Float natif ; on stocke en Double pour la précision
                    is Float -> json.put(key, value.toDouble())
                    is Long -> json.put(key, value)
                    is String -> json.put(key, value)
                    is Set<*> -> json.put(key, JSONArray(value.filterIsInstance<String>()))
                }
            }
            return json
        }

        private fun SharedPreferences.Editor.fromJson(json : JSONObject) : SharedPreferences.Editor {
            json.keys().forEach { key ->
                when (val v = json.get(key)) {
                    is Boolean -> putBoolean(key, v)
                    is Int -> putInt(key, v)
                    // Les entiers JSON reviennent parfois en Long selon la valeur
                    is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) putInt(key, v.toInt()) else putLong(key, v)
                    // Les Float ont été stockés en Double à l'export
                    is Double -> putFloat(key, v.toFloat())
                    is String -> putString(key, v)
                    is JSONArray -> putStringSet(key, (0 until v.length()).map { v.getString(it) }.toSet())
                }
            }
            return this
        }

        // ---- input.bin : lecture (Java serialization) → JSON ----

        private fun readInputBinAsJson(context : Context) : JSONObject? {
            val file = File("${context.applicationInfo.dataDir}/input.bin")
            if (!file.exists() || file.length() == 0L) return null
            return try {
                ObjectInputStream(FileInputStream(file)).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val controllers = ois.readObject() as HashMap<Int, Controller>
                    @Suppress("UNCHECKED_CAST")
                    val eventMap = ois.readObject() as HashMap<HostEvent?, GuestEvent?>

                    val json = JSONObject()
                    json.put("schema_version", 1)

                    val controllersJson = JSONObject()
                    controllers.forEach { (slot, controller) ->
                        val c = JSONObject()
                        c.put("type", controller.type.name)
                        // Null explicite pour la rétrocompatibilité à l'import
                        c.put("rumbleDeviceDescriptor", controller.rumbleDeviceDescriptor ?: JSONObject.NULL)
                        c.put("rumbleDeviceName", controller.rumbleDeviceName ?: JSONObject.NULL)
                        when (controller) {
                            is JoyConLeftController -> c.put("partnerId", controller.partnerId ?: JSONObject.NULL)
                            is JoyConRightController -> c.put("partnerId", controller.partnerId ?: JSONObject.NULL)
                            else -> Unit
                        }
                        controllersJson.put(slot.toString(), c)
                    }
                    json.put("controllers", controllersJson)

                    val eventsJson = JSONArray()
                    eventMap.forEach { (host, guest) ->
                        if (host == null || guest == null) return@forEach
                        val hostJson = when (host) {
                            is KeyHostEvent -> JSONObject()
                                .put("type", "KeyHostEvent")
                                .put("descriptor", host.descriptor)
                                .put("keyCode", host.keyCode)
                            is MotionHostEvent -> JSONObject()
                                .put("type", "MotionHostEvent")
                                .put("descriptor", host.descriptor)
                                .put("axis", host.axis)
                                .put("polarity", host.polarity)
                        }
                        val guestJson = when (guest) {
                            is ButtonGuestEvent -> JSONObject()
                                .put("type", "ButtonGuestEvent")
                                .put("controllerId", guest.id)
                                .put("button", guest.button.name)
                                .put("threshold", guest.threshold.toDouble())
                            is AxisGuestEvent -> JSONObject()
                                .put("type", "AxisGuestEvent")
                                .put("controllerId", guest.id)
                                .put("axis", guest.axis.name)
                                .put("polarity", guest.polarity)
                                .put("max", guest.max.toDouble())
                            else -> null
                        } ?: return@forEach

                        eventsJson.put(JSONObject().put("host", hostJson).put("guest", guestJson))
                    }
                    json.put("eventMap", eventsJson)
                    json
                }
            } catch (e : Exception) {
                Log.w(TAG, "Could not read input.bin, skipping input bindings: ${e.message}")
                null
            }
        }

        // ---- input.bin : JSON → écriture (Java serialization) ----

        private fun writeInputBinFromJson(context : Context, json : JSONObject) {
            val controllersJson = json.getJSONObject("controllers")
            val controllers = HashMap<Int, Controller>()

            controllersJson.keys().forEach { slot ->
                val slotInt = slot.toIntOrNull() ?: return@forEach
                val c = controllersJson.getJSONObject(slot)
                val type = runCatching { ControllerType.valueOf(c.getString("type")) }.getOrDefault(ControllerType.None)
                val rumbleDesc = if (c.isNull("rumbleDeviceDescriptor")) null else c.optString("rumbleDeviceDescriptor")
                val rumbleName = if (c.isNull("rumbleDeviceName")) null else c.optString("rumbleDeviceName")
                val partnerId = if (c.has("partnerId") && !c.isNull("partnerId")) c.optInt("partnerId") else null

                controllers[slotInt] = when (type) {
                    ControllerType.JoyConLeft -> JoyConLeftController(slotInt, partnerId)
                    ControllerType.JoyConRight -> JoyConRightController(slotInt, partnerId)
                    else -> Controller(slotInt, type, rumbleDesc, rumbleName)
                }
            }

            val eventsJson = json.getJSONArray("eventMap")
            val eventMap = HashMap<HostEvent?, GuestEvent?>()

            for (i in 0 until eventsJson.length()) {
                val entry = eventsJson.getJSONObject(i)
                val hostJson = entry.getJSONObject("host")
                val guestJson = entry.getJSONObject("guest")

                val host : HostEvent? = when (hostJson.getString("type")) {
                    "KeyHostEvent" -> KeyHostEvent(
                        descriptor = hostJson.getString("descriptor"),
                        keyCode = hostJson.getInt("keyCode")
                    )
                    "MotionHostEvent" -> MotionHostEvent(
                        descriptor = hostJson.getString("descriptor"),
                        axis = hostJson.getInt("axis"),
                        polarity = hostJson.getBoolean("polarity")
                    )
                    else -> null
                }

                val guest : GuestEvent? = when (guestJson.getString("type")) {
                    "ButtonGuestEvent" -> runCatching {
                        ButtonGuestEvent(
                            id = guestJson.getInt("controllerId"),
                            button = ButtonId.valueOf(guestJson.getString("button")),
                            threshold = guestJson.getDouble("threshold").toFloat()
                        )
                    }.getOrNull()
                    "AxisGuestEvent" -> runCatching {
                        AxisGuestEvent(
                            id = guestJson.getInt("controllerId"),
                            axis = AxisId.valueOf(guestJson.getString("axis")),
                            polarity = guestJson.getBoolean("polarity"),
                            max = guestJson.getDouble("max").toFloat()
                        )
                    }.getOrNull()
                    else -> null
                }

                if (host != null && guest != null) eventMap[host] = guest
            }

            ObjectOutputStream(File("${context.applicationInfo.dataDir}/input.bin").outputStream()).use { oos ->
                oos.writeObject(controllers)
                oos.writeObject(eventMap)
                oos.flush()
            }
        }

        private fun ZipOutputStream.putTextEntry(name : String, content : String) {
            putNextEntry(ZipEntry(name))
            write(content.toByteArray(Charsets.UTF_8))
            closeEntry()
        }
    }
}
