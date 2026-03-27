/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package org.stratoemu.strato.preference

import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.stratoemu.strato.R
import org.stratoemu.strato.adapter.GenericListItem
import org.stratoemu.strato.adapter.GpuDriverViewItem
import org.stratoemu.strato.adapter.SelectableGenericAdapter
import org.stratoemu.strato.adapter.SpacingItemDecoration
import org.stratoemu.strato.data.AppItem
import org.stratoemu.strato.data.AppItemTag
import org.stratoemu.strato.databinding.GpuDriverActivityBinding
import org.stratoemu.strato.settings.EmulationSettings
import org.stratoemu.strato.utils.GpuDriverHelper
import org.stratoemu.strato.utils.GpuDriverInstallResult
import org.stratoemu.strato.utils.WindowInsetsHelper
import org.stratoemu.strato.utils.serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * This activity is used to manage the installed gpu drivers and select one to use.
 */
@AndroidEntryPoint
class GpuDriverActivity : AppCompatActivity() {
    private val binding by lazy { GpuDriverActivityBinding.inflate(layoutInflater) }

    private val item by lazy { intent.extras?.serializable(AppItemTag) as AppItem? }

    private val adapter = SelectableGenericAdapter(0)

    lateinit var emulationSettings : EmulationSettings

    /**
     * The callback called after a user picked a driver to install.
     */
    private val installCallback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            it.data?.data?.let { uri ->
                val fileStream = contentResolver.openInputStream(uri) ?: return@let

                Snackbar.make(binding.root, getString(R.string.gpu_driver_install_inprogress), Snackbar.LENGTH_INDEFINITE).show()
                CoroutineScope(Dispatchers.IO).launch {
                    val result = GpuDriverHelper.installDriver(this@GpuDriverActivity, fileStream)
                    runOnUiThread {
                        Snackbar.make(binding.root, resolveInstallResultString(result), Snackbar.LENGTH_LONG).show()
                        if (result == GpuDriverInstallResult.Success)
                            populateAdapter()
                    }
                }
            }
        }
    }

    /**
     * Updates the [adapter] with the current list of installed drivers.
     */
    private fun populateAdapter() {
        val items : MutableList<GenericListItem<out ViewBinding>> = ArrayList()

        // Insert the system driver entry at the top of the list.
        items.add(GpuDriverViewItem(GpuDriverHelper.getSystemDriverMetadata(this)) {
            emulationSettings.gpuDriver = EmulationSettings.SYSTEM_GPU_DRIVER
        })

        if (emulationSettings.gpuDriver == EmulationSettings.SYSTEM_GPU_DRIVER) {
            adapter.selectedPosition = 0
        }

        GpuDriverHelper.getInstalledDrivers(this).onEachIndexed { index, (file, metadata) ->
            items.add(GpuDriverViewItem(metadata).apply {
                // Enable the delete button when configuring global settings only
                onDelete = if (emulationSettings.isGlobal) { _, wasChecked ->
                    if (wasChecked)
                        emulationSettings.gpuDriver = EmulationSettings.SYSTEM_GPU_DRIVER
                    file.deleteRecursively()
                } else null

                onClick = {
                    emulationSettings.gpuDriver = metadata.label
                }
            })

            if (emulationSettings.gpuDriver == metadata.label) {
                adapter.selectedPosition = index + 1 // Add 1 to account for the system driver entry
            }
        }

        adapter.setItems(items)
    }

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsHelper.applyToActivity(binding.root, binding.driverList)
        WindowInsetsHelper.addMargin(binding.addDriverButton, bottom = true)
        WindowInsetsHelper.addMargin(binding.getDriversButton, bottom = true)

        setSupportActionBar(binding.titlebar.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.gpu_driver_config)
            subtitle = item?.title
        }

        emulationSettings = if (item == null) {
            EmulationSettings.global
        } else {
            val appItem = item as AppItem
            EmulationSettings.forTitleId(appItem.titleId ?: appItem.key())
        }

        val layoutManager = LinearLayoutManager(this)
        binding.driverList.layoutManager = layoutManager
        binding.driverList.adapter = adapter

        var layoutDone = false // Tracks if the layout is complete to avoid retrieving invalid attributes
        binding.coordinatorLayout.viewTreeObserver.addOnTouchModeChangeListener { isTouchMode ->
            val layoutUpdate = {
                val params = binding.driverList.layoutParams as CoordinatorLayout.LayoutParams
                if (!isTouchMode) {
                    binding.titlebar.appBarLayout.setExpanded(true)
                    params.height = binding.coordinatorLayout.height - binding.titlebar.toolbar.height
                } else {
                    params.height = CoordinatorLayout.LayoutParams.MATCH_PARENT
                }

                binding.driverList.layoutParams = params
                binding.driverList.requestLayout()
            }

            if (!layoutDone) {
                binding.coordinatorLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        // We need to wait till the layout is done to get the correct height of the toolbar
                        binding.coordinatorLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        layoutUpdate()
                        layoutDone = true
                    }
                })
            } else {
                layoutUpdate()
            }
        }

        binding.driverList.addItemDecoration(SpacingItemDecoration(resources.getDimensionPixelSize(R.dimen.grid_padding)))

        binding.getDriversButton.setOnClickListener {
            startActivity(Intent(this, DriverFetcherActivity::class.java))
        }

        binding.addDriverButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                type = "application/zip"
            }
            installCallback.launch(intent)
        }

        populateAdapter()
    }

    override fun onResume() {
        super.onResume()
        populateAdapter()
    }

    private fun resolveInstallResultString(result : GpuDriverInstallResult) = when (result) {
        GpuDriverInstallResult.Success -> getString(R.string.gpu_driver_install_success)
        GpuDriverInstallResult.InvalidArchive -> getString(R.string.gpu_driver_install_invalid_archive)
        GpuDriverInstallResult.MissingMetadata -> getString(R.string.gpu_driver_install_missing_metadata)
        GpuDriverInstallResult.InvalidMetadata -> getString(R.string.gpu_driver_install_invalid_metadata)
        GpuDriverInstallResult.UnsupportedAndroidVersion -> getString(R.string.gpu_driver_install_unsupported_android_version)
        GpuDriverInstallResult.AlreadyInstalled -> getString(R.string.gpu_driver_install_already_installed)
    }
}
