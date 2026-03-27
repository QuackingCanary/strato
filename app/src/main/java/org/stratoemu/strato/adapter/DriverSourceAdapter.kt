/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors
 */

package org.stratoemu.strato.adapter

import android.animation.ObjectAnimator
import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import org.stratoemu.strato.R
import org.stratoemu.strato.data.DownloadState
import org.stratoemu.strato.data.DriverSource
import org.stratoemu.strato.data.DriverSourceState
import org.stratoemu.strato.data.GitHubAsset
import org.stratoemu.strato.data.GitHubRelease
import org.stratoemu.strato.data.ReleaseState
import org.stratoemu.strato.data.isInstalled
import org.stratoemu.strato.databinding.DriverFetcherHeaderBinding
import org.stratoemu.strato.databinding.DriverSourceItemBinding
import org.stratoemu.strato.databinding.ReleaseItemBinding
import org.stratoemu.strato.utils.GpuDriverInstallResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class DriverSourceAdapter(
    private val onExpandClick : (DriverSource) -> Unit,
    private val onReleaseExpandClick : (DriverSource, String) -> Unit,
    private val onDownloadClick : (DriverSource, String, GitHubAsset) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SOURCE = 1
    }

    private var items : List<DriverSourceState> = emptyList()
    private var gpuName : String = ""
    private val expandedChangelogs = mutableSetOf<String>()

    fun setGpuName(name : String) {
        gpuName = name
        notifyItemChanged(0)
    }

    fun submitList(newItems : List<DriverSourceState>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o : Int, n : Int) = items[o].source.id == newItems[n].source.id
            override fun areContentsTheSame(o : Int, n : Int) = items[o] == newItems[n]
            override fun getChangePayload(o : Int, n : Int) : Any? {
                val new = newItems[n]
                // If only the download progress changed, return progress as payload to skip full rebind
                return if (new.downloadState is DownloadState.Downloading &&
                    items[o].copy(downloadState = new.downloadState) == new
                ) new.downloadState.progress else null
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position : Int, count : Int) = notifyItemRangeInserted(position + 1, count)
            override fun onRemoved(position : Int, count : Int) = notifyItemRangeRemoved(position + 1, count)
            override fun onMoved(from : Int, to : Int) = notifyItemMoved(from + 1, to + 1)
            override fun onChanged(position : Int, count : Int, payload : Any?) = notifyItemRangeChanged(position + 1, count, payload)
        })
    }

    override fun getItemCount() = items.size + 1
    override fun getItemViewType(position : Int) = if (position == 0) TYPE_HEADER else TYPE_SOURCE

    override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderViewHolder(DriverFetcherHeaderBinding.inflate(inflater, parent, false))
        else
            SourceViewHolder(DriverSourceItemBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder : RecyclerView.ViewHolder, position : Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(gpuName)
            is SourceViewHolder -> holder.bind(items[position - 1])
        }
    }

    override fun onBindViewHolder(holder : RecyclerView.ViewHolder, position : Int, payloads : MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is SourceViewHolder) {
            val progress = payloads.filterIsInstance<Int>().lastOrNull()
            if (progress != null) {
                holder.updateDownloadProgress(progress)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class HeaderViewHolder(private val binding : DriverFetcherHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(name : String) {
            binding.gpuNameText.text = name.ifBlank { "…" }
        }
    }

    inner class SourceViewHolder(private val binding : DriverSourceItemBinding) : RecyclerView.ViewHolder(binding.root) {

        private var activeDownloadProgressBar : LinearProgressIndicator? = null
        private var previousIsExpanded : Boolean? = null
        private var wasInstalled : Boolean = false
        private var skeletonAnimator : ObjectAnimator? = null

        fun updateDownloadProgress(progress : Int) {
            activeDownloadProgressBar?.setProgressCompat(progress, true)
        }

        fun bind(state : DriverSourceState) {
            val source = state.source
            binding.driverName.text = source.name
            binding.driverSubtitle.text = source.subtitle

            binding.headerLayout.setOnClickListener { onExpandClick(source) }

            // Expansion animation
            val isExpanded = state.isExpanded
            val prevExpanded = previousIsExpanded
            previousIsExpanded = isExpanded
            binding.expandButton.animate().rotation(if (isExpanded) 180f else 0f).setDuration(200).start()
            if (prevExpanded != null && prevExpanded != isExpanded) {
                if (isExpanded) {
                    binding.expandableContent.visibility = View.VISIBLE
                    binding.expandableContent.alpha = 0f
                    binding.expandableContent.animate().alpha(1f).setDuration(200).start()
                } else {
                    binding.expandableContent.animate()
                        .alpha(0f).setDuration(150)
                        .withEndAction {
                            binding.expandableContent.visibility = View.GONE
                            binding.expandableContent.alpha = 1f
                        }.start()
                }
            } else {
                binding.expandableContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }

            // Card stroke tint when installed
            val isInstalled = state.downloadState.isInstalled()
            val strokeColor = MaterialColors.getColor(binding.root, MaterialR.attr.colorPrimary, 0)
            binding.root.strokeWidth = if (isInstalled)
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, binding.root.resources.displayMetrics).toInt()
            else 0
            binding.root.strokeColor = strokeColor

            // Installed chip with pop-in animation
            if (isInstalled && !wasInstalled) {
                binding.installedIndicator.visibility = View.VISIBLE
                binding.installedIndicator.scaleX = 0f
                binding.installedIndicator.scaleY = 0f
                binding.installedIndicator.alpha = 0f
                binding.installedIndicator.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(300).start()
            } else {
                binding.installedIndicator.visibility = if (isInstalled) View.VISIBLE else View.GONE
            }
            wasInstalled = isInstalled

            bindReleaseState(state)
            bindDownloadState(state)
        }

        private fun bindReleaseState(state : DriverSourceState) {
            when (val rs = state.releaseState) {
                is ReleaseState.Idle -> {
                    binding.headerLoading.visibility = View.GONE
                    binding.versionChip.visibility = View.GONE
                    binding.errorText.visibility = View.GONE
                    binding.releasesSkeleton.visibility = View.GONE
                    stopSkeletonAnimation()
                    binding.releasesContainer.visibility = View.GONE
                }
                is ReleaseState.Loading -> {
                    binding.headerLoading.visibility = if (state.isExpanded) View.VISIBLE else View.GONE
                    binding.versionChip.visibility = View.GONE
                    binding.errorText.visibility = View.GONE
                    binding.releasesContainer.visibility = View.GONE
                    if (state.isExpanded) {
                        binding.releasesSkeleton.visibility = View.VISIBLE
                        startSkeletonAnimation()
                    } else {
                        binding.releasesSkeleton.visibility = View.GONE
                        stopSkeletonAnimation()
                    }
                }
                is ReleaseState.Error -> {
                    binding.headerLoading.visibility = View.GONE
                    binding.versionChip.visibility = View.GONE
                    binding.releasesSkeleton.visibility = View.GONE
                    stopSkeletonAnimation()
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = binding.root.context.getString(R.string.driver_load_error, rs.message)
                    val warningDrawable = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_warning)?.mutate()?.also { d ->
                        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, binding.root.resources.displayMetrics).toInt()
                        DrawableCompat.setTint(d, MaterialColors.getColor(binding.root, MaterialR.attr.colorError, 0))
                        d.setBounds(0, 0, size, size)
                    }
                    binding.errorText.setCompoundDrawablesRelative(warningDrawable, null, null, null)
                    binding.errorText.compoundDrawablePadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, binding.root.resources.displayMetrics).toInt()
                    binding.releasesContainer.visibility = View.GONE
                }
                is ReleaseState.Success -> {
                    binding.headerLoading.visibility = View.GONE
                    binding.versionChip.visibility = View.GONE
                    binding.errorText.visibility = View.GONE
                    binding.releasesSkeleton.visibility = View.GONE
                    stopSkeletonAnimation()
                    if (rs.releases.isNotEmpty()) {
                        binding.releasesContainer.visibility = View.VISIBLE
                        populateReleases(rs.releases, state)
                    } else {
                        binding.releasesContainer.visibility = View.GONE
                    }
                }
            }
        }

        private fun bindDownloadState(state : DriverSourceState) {
            // Only lock all buttons during an active download/install.
            // Per-release enabled state (installed release grayed) is handled in populateReleases.
            if (state.downloadState is DownloadState.Downloading ||
                state.downloadState is DownloadState.Installing
            ) disableReleaseButtons()
        }

        private fun startSkeletonAnimation() {
            if (skeletonAnimator?.isRunning == true) return
            skeletonAnimator = ObjectAnimator.ofFloat(binding.releasesSkeleton, "alpha", 0.3f, 1f).apply {
                duration = 700
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        }

        private fun stopSkeletonAnimation() {
            skeletonAnimator?.cancel()
            skeletonAnimator = null
            binding.releasesSkeleton.alpha = 1f
        }

        private fun populateReleases(releases : List<GitHubRelease>, state : DriverSourceState) {
            val container = binding.releasesContainer
            container.removeAllViews()
            activeDownloadProgressBar = null
            val inflater = LayoutInflater.from(container.context)
            val isActiveDownload = state.downloadState is DownloadState.Downloading ||
                state.downloadState is DownloadState.Installing

            releases.forEach { release ->
                val item = ReleaseItemBinding.inflate(inflater, container, false)

                item.releaseTagChip.text = release.tagName
                item.releaseDate.text = formatDate(release.publishedAt)
                val isThisInstalled = state.downloadState.isInstalled() &&
                    state.downloadingReleaseTag == release.tagName
                item.releaseInstalledIndicator.visibility = if (isThisInstalled) View.VISIBLE else View.GONE

                val isExpanded = state.expandedReleaseTag == release.tagName
                item.releaseExpandable.visibility = if (isExpanded) View.VISIBLE else View.GONE
                item.releaseExpandArrow.rotation = if (isExpanded) 180f else 0f

                item.releaseHeader.setOnClickListener {
                    onReleaseExpandClick(state.source, release.tagName)
                }

                if (isExpanded) {
                    val body = release.body
                    if (!body.isNullOrBlank()) {
                        val changelogKey = "${state.source.id}_${release.tagName}"
                        val isChangelogExpanded = expandedChangelogs.contains(changelogKey)
                        val needsSeeMore = body.count { it == '\n' } >= 6 || body.length > 400
                        item.releaseChangelog.visibility = View.VISIBLE
                        item.releaseChangelog.text = body.trim()
                        item.releaseChangelog.maxLines = if (isChangelogExpanded) Int.MAX_VALUE else 6
                        if (needsSeeMore) {
                            item.seeMoreButton.visibility = View.VISIBLE
                            item.seeMoreButton.setText(if (isChangelogExpanded) R.string.see_less else R.string.see_more)
                            val iconRes = if (isChangelogExpanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
                            val chevron = ContextCompat.getDrawable(container.context, iconRes)!!.mutate().also { d ->
                                val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, container.resources.displayMetrics).toInt()
                                DrawableCompat.setTint(d, MaterialColors.getColor(item.root, MaterialR.attr.colorPrimary, 0))
                                d.setBounds(0, 0, size, size)
                            }
                            item.seeMoreButton.setCompoundDrawablesRelative(chevron, null, null, null)
                            item.seeMoreButton.compoundDrawablePadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, container.resources.displayMetrics).toInt()
                            item.seeMoreButton.setOnClickListener {
                                if (isChangelogExpanded) expandedChangelogs.remove(changelogKey)
                                else expandedChangelogs.add(changelogKey)
                                val pos = items.indexOfFirst { it.source.id == state.source.id }
                                if (pos != -1) notifyItemChanged(pos + 1)
                            }
                        } else {
                            item.seeMoreButton.visibility = View.GONE
                        }
                    } else {
                        item.releaseChangelog.visibility = View.GONE
                        item.seeMoreButton.visibility = View.GONE
                    }

                    val zipAssets = release.assets.filter { it.name.endsWith(".zip", ignoreCase = true) }
                    item.releaseDownloadButtons.removeAllViews()
                    zipAssets.forEach { asset ->
                        val btn = MaterialButton(container.context).apply {
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).also { it.bottomMargin = container.resources.getDimensionPixelSize(R.dimen.grid_padding) }
                            text = formatAssetLabel(asset)
                            setIconResource(R.drawable.ic_download)
                            isEnabled = !isActiveDownload && !isThisInstalled
                            setOnClickListener { onDownloadClick(state.source, release.tagName, asset) }
                        }
                        item.releaseDownloadButtons.addView(btn)
                    }

                    val isThisRelease = state.downloadingReleaseTag == release.tagName
                    val ctx = container.context
                    val colorPrimary = MaterialColors.getColor(item.root, MaterialR.attr.colorPrimary, 0)
                    val colorError = MaterialColors.getColor(item.root, MaterialR.attr.colorError, 0)
                    when (val ds = state.downloadState) {
                        is DownloadState.Downloading -> if (isThisRelease) {
                            item.releaseDownloadProgress.visibility = View.VISIBLE
                            item.releaseDownloadProgress.isIndeterminate = false
                            item.releaseDownloadProgress.setProgressCompat(ds.progress, false)
                            activeDownloadProgressBar = item.releaseDownloadProgress
                            item.releaseDownloadStatus.visibility = View.GONE
                        }
                        is DownloadState.Installing -> if (isThisRelease) {
                            item.releaseDownloadProgress.visibility = View.VISIBLE
                            item.releaseDownloadProgress.isIndeterminate = true
                            item.releaseDownloadStatus.visibility = View.GONE
                        }
                        is DownloadState.Done -> if (isThisRelease) {
                            item.releaseDownloadProgress.visibility = View.GONE
                            item.releaseDownloadStatus.visibility = View.VISIBLE
                            item.releaseDownloadStatus.text = resolveInstallResultString(ctx, ds.result)
                            item.releaseDownloadStatus.setTextColor(if (state.downloadState.isInstalled()) colorPrimary else colorError)
                        }
                        is DownloadState.Error -> if (isThisRelease) {
                            item.releaseDownloadProgress.visibility = View.GONE
                            item.releaseDownloadStatus.visibility = View.VISIBLE
                            item.releaseDownloadStatus.text = ds.message
                            item.releaseDownloadStatus.setTextColor(colorError)
                        }
                        else -> {
                            item.releaseDownloadProgress.visibility = View.GONE
                            item.releaseDownloadStatus.visibility = View.GONE
                        }
                    }
                }

                container.addView(item.root)
            }
        }

        private fun disableReleaseButtons() {
            val container = binding.releasesContainer
            for (i in 0 until container.childCount) {
                val releaseRow = container.getChildAt(i)
                val buttonsContainer = releaseRow.findViewById<LinearLayout>(R.id.release_download_buttons)
                for (j in 0 until (buttonsContainer?.childCount ?: 0)) {
                    buttonsContainer?.getChildAt(j)?.isEnabled = false
                }
            }
        }

        private fun formatDate(publishedAt : String) : String {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(publishedAt) ?: return publishedAt.take(10)
                val diffMs = System.currentTimeMillis() - date.time
                val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)
                when {
                    diffDays < 1 -> "today"
                    diffDays < 2 -> "yesterday"
                    diffDays < 7 -> "$diffDays days ago"
                    diffDays < 14 -> "1 week ago"
                    diffDays < 30 -> "${diffDays / 7} weeks ago"
                    diffDays < 60 -> "1 month ago"
                    diffDays < 365 -> "${diffDays / 30} months ago"
                    diffDays < 730 -> "1 year ago"
                    else -> "${diffDays / 365} years ago"
                }
            } catch (_: Exception) {
                publishedAt.take(10)
            }
        }

        private fun formatAssetLabel(asset : GitHubAsset) : String {
            val mb = asset.size / (1024.0 * 1024.0)
            return "${asset.name}  •  %.1f MB".format(mb)
        }

        private fun resolveInstallResultString(ctx : Context, result : GpuDriverInstallResult) = when (result) {
            GpuDriverInstallResult.Success -> ctx.getString(R.string.gpu_driver_install_success)
            GpuDriverInstallResult.AlreadyInstalled -> ctx.getString(R.string.gpu_driver_install_already_installed)
            GpuDriverInstallResult.InvalidArchive -> ctx.getString(R.string.gpu_driver_install_invalid_archive)
            GpuDriverInstallResult.MissingMetadata -> ctx.getString(R.string.gpu_driver_install_missing_metadata)
            GpuDriverInstallResult.InvalidMetadata -> ctx.getString(R.string.gpu_driver_install_invalid_metadata)
            GpuDriverInstallResult.UnsupportedAndroidVersion -> ctx.getString(R.string.gpu_driver_install_unsupported_android_version)
        }
    }
}
