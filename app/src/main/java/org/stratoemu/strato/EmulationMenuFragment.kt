/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package org.stratoemu.strato

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stratoemu.strato.data.AppItemTag
import org.stratoemu.strato.settings.SettingsActivity
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.stratoemu.strato.databinding.FragmentEmulationMenuBinding

class EmulationMenuFragment : DialogFragment() {

    companion object {
        const val TAG = "emulation_menu"
        private const val ARG_GAME_TITLE = "game_title"
        private const val DIM_AMOUNT = 0.5f
        private const val DURATION_ENTER_FALLBACK = 600L
        private const val DURATION_EXIT_FALLBACK = 450L

        private val FALLBACK_INTERP_ENTER : TimeInterpolator = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
        private val FALLBACK_INTERP_EXIT : TimeInterpolator = PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)

        fun newInstance(gameTitle : String) = EmulationMenuFragment().apply {
            arguments = Bundle().apply { putString(ARG_GAME_TITLE, gameTitle) }
        }
    }

    private var _binding : FragmentEmulationMenuBinding? = null
    private val binding get() = _binding!!

private val durationEnter : Long by lazy {
        MotionUtils.resolveThemeDuration(requireContext(), com.google.android.material.R.attr.motionDurationLong4, DURATION_ENTER_FALLBACK.toInt()).toLong()
    }
    private val durationExit : Long by lazy {
        MotionUtils.resolveThemeDuration(requireContext(), com.google.android.material.R.attr.motionDurationLong1, DURATION_EXIT_FALLBACK.toInt()).toLong()
    }
    private val interpEnter : TimeInterpolator by lazy {
        MotionUtils.resolveThemeInterpolator(requireContext(), com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator, FALLBACK_INTERP_ENTER)
    }
    private val interpExit : TimeInterpolator by lazy {
        MotionUtils.resolveThemeInterpolator(requireContext(), com.google.android.material.R.attr.motionEasingEmphasizedAccelerateInterpolator, FALLBACK_INTERP_EXIT)
    }

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.EmulationMenuDialogTheme)
    }

    override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View {
        _binding = FragmentEmulationMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view : View, savedInstanceState : Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val emulationActivity = requireActivity() as EmulationActivity

        binding.menuGameTitle.text = arguments?.getString(ARG_GAME_TITLE) ?: ""

        binding.menuRoot.translationX = -panelWidth()

        applyPanelBackground()

        ViewCompat.setOnApplyWindowInsetsListener(binding.menuPanel) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePaddingRelative(
                start = cutout.left,
                top = maxOf(cutout.top, statusBarTop) + resources.getDimensionPixelSize(R.dimen.emulation_menu_header_padding_top)
            )
            insets
        }

        binding.menuItemQuit.setOnClickListener {
            emulationActivity.returnFromEmulation()
        }

        syncPauseButton(emulationActivity)

        binding.menuItemPause.setOnClickListener {
            if (emulationActivity.isEmulatorPaused) {
                emulationActivity.resumeEmulator()
            } else {
                emulationActivity.pauseEmulator()
            }
            syncPauseButton(emulationActivity)
        }

        binding.menuItemController.setOnClickListener {
            val gameTitle = arguments?.getString(ARG_GAME_TITLE) ?: ""
            val options = arrayOf(
                getString(R.string.controller_scope_global),
                getString(R.string.controller_scope_per_game, gameTitle)
            )
            var selectedWhich = -1
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.controller_scope_title)
                .setItems(options) { _, which -> selectedWhich = which }
                .setOnDismissListener {
                    if (selectedWhich < 0) return@setOnDismissListener
                    val intent = Intent(requireContext(), SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_INPUT_ONLY_MODE, true)
                    if (selectedWhich == 1) {
                        intent.putExtra(SettingsActivity.EXTRA_GAME_KEY, emulationActivity.gameInputKey())
                        emulationActivity.scheduleInputReload()
                    }
                    dismiss()
                    startActivity(intent)
                }
                .show()
        }

        binding.menuItemSettings.setOnClickListener {
            emulationActivity.scheduleNativeSettingsUpdate()
            dismiss()
            startActivity(
                Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_EMULATION_MODE, true)
            )
        }

        binding.menuItemGameSettings.setOnClickListener {
            dismiss()
            startActivity(
                Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(AppItemTag, emulationActivity.item)
                    .putExtra(SettingsActivity.EXTRA_EMULATION_MODE, true)
            )
        }
    }

    private fun applyPanelBackground() {
        val cornerRadius = resources.getDimension(R.dimen.emulation_menu_corner_radius)
        val shapeModel = ShapeAppearanceModel.builder()
            .setTopRightCorner(CornerFamily.ROUNDED, cornerRadius)
            .setBottomRightCorner(CornerFamily.ROUNDED, cornerRadius)
            .build()
        val surfaceColor = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, Color.WHITE)
        binding.menuPanel.background = MaterialShapeDrawable(shapeModel).apply {
            fillColor = ColorStateList.valueOf(surfaceColor)
        }

        binding.menuRoot.onDragProgress = { progress ->
            setDim(DIM_AMOUNT * (1f - progress))
        }

        binding.menuRoot.onSwipeDismiss = {
            binding.menuRoot.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setGravity(Gravity.START)
            setLayout(resources.getDimensionPixelSize(R.dimen.emulation_menu_touch_width), ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                attributes = attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    it.dimAmount = 0f
                }
                setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    attributes = attributes.also {
                        it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        it.dimAmount = 0f
                    }
                }
            }
        }

        animateEnter()
        binding.menuRoot.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = animateExit { dismiss() }
        })
    }

    private fun animateEnter() {
        binding.menuRoot.animate()
            .translationX(0f)
            .setDuration(durationEnter)
            .setInterpolator(interpEnter)
            .start()

        ValueAnimator.ofFloat(0f, DIM_AMOUNT).apply {
            duration = durationEnter
            interpolator = interpEnter
            addUpdateListener { setDim(it.animatedValue as Float) }
            start()
        }
    }

    private fun animateExit(onEnd : () -> Unit) {
        binding.menuRoot.animate()
            .translationX(-panelWidth())
            .setDuration(durationExit)
            .setInterpolator(interpExit)
            .withEndAction(onEnd)
            .start()

        ValueAnimator.ofFloat(DIM_AMOUNT, 0f).apply {
            duration = durationExit
            interpolator = interpExit
            addUpdateListener { setDim(it.animatedValue as Float) }
            start()
        }
    }

    private fun syncPauseButton(emulationActivity : EmulationActivity) {
        val paused = emulationActivity.isEmulatorPaused
        binding.menuPauseIcon.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        binding.menuPauseText.setText(if (paused) R.string.resume else R.string.pause)
    }

    private fun panelWidth() : Float = resources.getDimensionPixelSize(R.dimen.emulation_menu_width).toFloat()

    private fun setDim(amount : Float) {
        dialog?.window?.let { w ->
            w.attributes = w.attributes.also { it.dimAmount = amount }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
