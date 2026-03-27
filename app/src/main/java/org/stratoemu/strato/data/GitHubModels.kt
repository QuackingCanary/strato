/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors
 */

package org.stratoemu.strato.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName : String,
    @SerialName("name") val name : String? = null,
    @SerialName("body") val body : String? = null,
    @SerialName("published_at") val publishedAt : String,
    val assets : List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name : String,
    @SerialName("browser_download_url") val downloadUrl : String,
    val size : Long
)
