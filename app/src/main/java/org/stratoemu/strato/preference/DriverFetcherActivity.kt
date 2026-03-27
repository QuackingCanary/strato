/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2024 Strato Team and Contributors
 */

package org.stratoemu.strato.preference

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.stratoemu.strato.R
import org.stratoemu.strato.adapter.DriverSourceAdapter
import org.stratoemu.strato.adapter.SpacingItemDecoration
import org.stratoemu.strato.data.ReleaseState
import org.stratoemu.strato.databinding.ActivityDriverFetcherBinding
import org.stratoemu.strato.model.DriverFetcherViewModel
import org.stratoemu.strato.utils.WindowInsetsHelper

@AndroidEntryPoint
class DriverFetcherActivity : AppCompatActivity() {

    private val binding by lazy { ActivityDriverFetcherBinding.inflate(layoutInflater) }
    private val viewModel : DriverFetcherViewModel by viewModels()

    private val adapter = DriverSourceAdapter(
        onExpandClick = { source -> viewModel.toggleExpanded(source.id) },
        onReleaseExpandClick = { source, tag -> viewModel.toggleReleaseExpanded(source.id, tag) },
        onDownloadClick = { source, tag, asset -> viewModel.downloadAndInstall(source.id, tag, asset) }
    )

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsHelper.applyToActivity(binding.root, binding.sourcesList)

        setSupportActionBar(binding.titlebar.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.driver_fetcher_title)
        }

        binding.sourcesList.layoutManager = LinearLayoutManager(this)
        binding.sourcesList.adapter = adapter
        binding.sourcesList.addItemDecoration(SpacingItemDecoration(resources.getDimensionPixelSize(R.dimen.grid_padding)))

        var layoutDone = false
        binding.coordinatorLayout.viewTreeObserver.addOnTouchModeChangeListener { isTouchMode ->
            val update = {
                val params = binding.swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
                if (!isTouchMode) {
                    binding.titlebar.appBarLayout.setExpanded(true)
                    params.height = binding.coordinatorLayout.height - binding.titlebar.toolbar.height
                } else {
                    params.height = CoordinatorLayout.LayoutParams.MATCH_PARENT
                }
                binding.swipeRefresh.layoutParams = params
                binding.swipeRefresh.requestLayout()
            }
            if (!layoutDone) {
                binding.coordinatorLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        binding.coordinatorLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        update()
                        layoutDone = true
                    }
                })
            } else {
                update()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshAllReleases()
        }

        lifecycleScope.launch {
            viewModel.sourceStates.collect { states ->
                adapter.submitList(states)
                if (binding.swipeRefresh.isRefreshing) {
                    val anyLoading = states.any { it.releaseState is ReleaseState.Loading }
                    if (!anyLoading) binding.swipeRefresh.isRefreshing = false
                }
            }
        }
        lifecycleScope.launch {
            viewModel.gpuName.collect { name ->
                adapter.setGpuName(name)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalledStates()
    }
}
