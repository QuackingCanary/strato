/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors
 */

package org.stratoemu.strato.model

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.stratoemu.strato.data.DownloadState
import org.stratoemu.strato.data.DriverSource
import org.stratoemu.strato.data.DriverSourceState
import org.stratoemu.strato.data.GitHubAsset
import org.stratoemu.strato.data.GitHubRelease
import org.stratoemu.strato.data.ReleaseState
import org.stratoemu.strato.data.isInstalled
import org.stratoemu.strato.utils.GpuDriverHelper
import org.stratoemu.strato.utils.GpuDriverInstallResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private val ADRENO_GPU_MODEL_PATH = File("/sys/class/kgsl/kgsl-3d0/gpu_model")
private val MALI_GPU_INFO_PATH = File("/sys/class/misc/mali0/device/gpuinfo")

@HiltViewModel
class DriverFetcherViewModel @Inject constructor(
    application : Application
) : AndroidViewModel(application) {

    companion object {
        val DRIVER_SOURCES = listOf(
            DriverSource(
                id = "turnip_adreno",
                name = "Mesa Turnip",
                subtitle = "Open-source Vulkan driver for Adreno GPUs",
                repoOwner = "K11MCH1",
                repoName = "AdrenoToolsDrivers"
            ),
            DriverSource(
                id = "stevenMXZ_adreno",
                name = "Adreno Tools Drivers",
                subtitle = "Adreno Vulkan driver builds by StevenMXZ",
                repoOwner = "StevenMXZ",
                repoName = "Adreno-Tools-Drivers"
            ),
            DriverSource(
                id = "mrpurple_turnip",
                name = "Purple Turnip",
                subtitle = "Mesa Turnip builds by MrPurple666",
                repoOwner = "MrPurple666",
                repoName = "purple-turnip"
            ),
            DriverSource(
                id = "crueter_gamehub",
                name = "GameHub 8 Elite Drivers",
                subtitle = "Vulkan drivers for Snapdragon 8 Elite",
                repoOwner = "crueter",
                repoName = "GameHub-8Elite-Drivers"
            ),
            DriverSource(
                id = "whitebelyash_freedreno_turnip",
                name = "Freedreno Turnip CI",
                subtitle = "Mesa Turnip CI builds by whitebelyash",
                repoOwner = "whitebelyash",
                repoName = "freedreno_turnip-CI"
            )
        )

        private const val PREFS_NAME = "gpu_driver_fetcher"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sourceStates = MutableStateFlow(
        DRIVER_SOURCES.map { source ->
            DriverSourceState(
                source = source,
                downloadState = if (prefs.getBoolean(source.id, false))
                    DownloadState.Done(GpuDriverInstallResult.AlreadyInstalled)
                else
                    DownloadState.Idle,
                downloadingReleaseTag = prefs.getString("${source.id}_release_tag", null)
            )
        }
    )
    val sourceStates : StateFlow<List<DriverSourceState>> = _sourceStates.asStateFlow()

    private val _gpuName = MutableStateFlow("")
    val gpuName : StateFlow<String> = _gpuName.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _gpuName.value = readGpuName() }
        DRIVER_SOURCES.forEach { source -> fetchRelease(source.id) }
    }

    private fun readGpuName() : String {
        try { if (ADRENO_GPU_MODEL_PATH.canRead()) return ADRENO_GPU_MODEL_PATH.readText().trim() } catch (_: Exception) {}
        try { if (MALI_GPU_INFO_PATH.canRead()) return MALI_GPU_INFO_PATH.readText().trim() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it.isNotBlank() }?.let { return it }
        return GpuDriverHelper.getSystemDriverMetadata(getApplication()).vendor.takeIf { it.isNotBlank() } ?: ""
    }

    fun toggleExpanded(sourceId : String) {
        val current = _sourceStates.value.toMutableList()
        val idx = current.indexOfFirst { it.source.id == sourceId }
        if (idx == -1) return
        val state = current[idx]
        val shouldOpen = !state.isExpanded
        // Accordion: collapse any other expanded source when opening this one
        if (shouldOpen) {
            current.forEachIndexed { i, s ->
                if (i != idx && s.isExpanded) current[i] = s.copy(isExpanded = false)
            }
        }
        when (state.releaseState) {
            is ReleaseState.Idle -> {
                current[idx] = state.copy(isExpanded = true)
                _sourceStates.value = current
                fetchRelease(sourceId)
            }
            is ReleaseState.Loading -> {
                current[idx] = state.copy(isExpanded = shouldOpen)
                _sourceStates.value = current
            }
            is ReleaseState.Error -> {
                current[idx] = state.copy(isExpanded = true)
                _sourceStates.value = current
                fetchRelease(sourceId)
            }
            is ReleaseState.Success -> {
                current[idx] = state.copy(isExpanded = shouldOpen)
                _sourceStates.value = current
            }
        }
    }

    fun refreshAllReleases() {
        _sourceStates.value
            .filter { it.isExpanded }
            .forEach { fetchRelease(it.source.id) }
    }

    fun downloadAndInstall(sourceId : String, releaseTag : String, asset : GitHubAsset) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            updateState(sourceId) { copy(downloadState = DownloadState.Downloading(0), downloadingReleaseTag = releaseTag) }
            try {
                val tempFile = File(context.cacheDir, "driver_dl_${asset.name}")
                withContext(Dispatchers.IO) {
                    val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                        connect()
                    }
                    val total = asset.size
                    var downloaded = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var n : Int
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val pct = (downloaded * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) {
                                            updateState(sourceId) { copy(downloadState = DownloadState.Downloading(pct)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                updateState(sourceId) { copy(downloadState = DownloadState.Installing) }
                val labelsBefore = withContext(Dispatchers.IO) {
                    GpuDriverHelper.getInstalledDrivers(context).keys.map { it.name }.toSet()
                }
                val result = withContext(Dispatchers.IO) { GpuDriverHelper.installDriver(context, tempFile) }
                tempFile.delete()
                if (result == GpuDriverInstallResult.Success || result == GpuDriverInstallResult.AlreadyInstalled) {
                    prefs.edit {
                        putBoolean(sourceId, true)
                        putString("${sourceId}_release_tag", releaseTag)
                    }
                }
                if (result == GpuDriverInstallResult.Success) {
                    val labelsAfter = withContext(Dispatchers.IO) {
                        GpuDriverHelper.getInstalledDrivers(context).keys.map { it.name }.toSet()
                    }
                    val newLabel = (labelsAfter - labelsBefore).firstOrNull()
                    if (newLabel != null) prefs.edit { putString("${sourceId}_label", newLabel) }
                }
                updateState(sourceId) { copy(downloadState = DownloadState.Done(result)) }
            } catch (e : Exception) {
                updateState(sourceId) { copy(downloadState = DownloadState.Error(e.message ?: "Download failed")) }
            }
        }
    }

    /**
     * Re-checks the filesystem against persisted installed flags.
     * Clears the installed flag if the matching driver was deleted in GpuDriverActivity.
     */
    fun refreshInstalledStates() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val installedDirNames = withContext(Dispatchers.IO) {
                GpuDriverHelper.getInstalledDrivers(context).keys.map { it.name }.toSet()
            }
            val current = _sourceStates.value.toMutableList()
            current.forEachIndexed { idx, state ->
                if (!state.downloadState.isInstalled()) return@forEachIndexed
                val storedLabel = prefs.getString("${state.source.id}_label", null) ?: return@forEachIndexed
                if (!installedDirNames.contains(storedLabel)) {
                    prefs.edit {
                        remove(state.source.id)
                        remove("${state.source.id}_label")
                        remove("${state.source.id}_release_tag")
                    }
                    current[idx] = state.copy(downloadState = DownloadState.Idle, downloadingReleaseTag = null)
                }
            }
            _sourceStates.value = current
        }
    }

    fun toggleReleaseExpanded(sourceId : String, tagName : String) {
        updateState(sourceId) {
            copy(expandedReleaseTag = if (expandedReleaseTag == tagName) null else tagName)
        }
    }

    private fun fetchRelease(sourceId : String) {
        val source = DRIVER_SOURCES.firstOrNull { it.id == sourceId } ?: return
        updateState(sourceId) { copy(releaseState = ReleaseState.Loading) }
        viewModelScope.launch {
            val result = loadReleases("https://api.github.com/repos/${source.repoOwner}/${source.repoName}/releases?per_page=8")
            updateState(sourceId) {
                copy(
                    releaseState = result.fold(
                        onSuccess = { ReleaseState.Success(it) },
                        onFailure = { ReleaseState.Error(it.message ?: "Failed to load") }
                    )
                )
            }
        }
    }

    private suspend fun loadReleases(apiUrl : String) : Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "StratoEmu")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            if (conn.responseCode == 200) {
                Result.success(json.decodeFromString<List<GitHubRelease>>(conn.inputStream.bufferedReader().readText()))
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
        } catch (e : Exception) {
            Result.failure(e)
        }
    }

    private fun updateState(sourceId : String, block : DriverSourceState.() -> DriverSourceState) {
        val current = _sourceStates.value.toMutableList()
        val idx = current.indexOfFirst { it.source.id == sourceId }
        if (idx != -1) {
            current[idx] = current[idx].block()
            _sourceStates.value = current
        }
    }
}
