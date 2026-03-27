/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors
 */

package org.stratoemu.strato.data

import org.stratoemu.strato.utils.GpuDriverInstallResult

data class DriverSource(
    val id : String,
    val name : String,
    val subtitle : String,
    val repoOwner : String,
    val repoName : String
)

sealed class ReleaseState {
    object Idle : ReleaseState()
    object Loading : ReleaseState()
    data class Success(val releases : List<GitHubRelease>) : ReleaseState()
    data class Error(val message : String) : ReleaseState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress : Int) : DownloadState()
    object Installing : DownloadState()
    data class Done(val result : GpuDriverInstallResult) : DownloadState()
    data class Error(val message : String) : DownloadState()
}

data class DriverSourceState(
    val source : DriverSource,
    val releaseState : ReleaseState = ReleaseState.Idle,
    val downloadState : DownloadState = DownloadState.Idle,
    val isExpanded : Boolean = false,
    val expandedReleaseTag : String? = null,
    val downloadingReleaseTag : String? = null
)

fun DownloadState.isInstalled() : Boolean =
    this is DownloadState.Done &&
        (result == GpuDriverInstallResult.Success || result == GpuDriverInstallResult.AlreadyInstalled)
