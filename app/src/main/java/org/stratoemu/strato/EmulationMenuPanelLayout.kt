/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package org.stratoemu.strato

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

class EmulationMenuPanelLayout @JvmOverloads constructor(
    context : Context, attrs : AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val DISMISS_DRAG_FRACTION = 0.35f
        private const val DISMISS_FLING_VELOCITY = -800f
        private const val DISMISS_ANIMATION_DURATION = 180L
        private const val SNAP_BACK_DURATION = 150L
    }

    var onDragProgress : ((Float) -> Unit)? = null
    var onSwipeDismiss : (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startRawX = 0f
    private var startRawY = 0f
    private var isDragging = false
    private var velocityTracker : VelocityTracker? = null

    private val panelWidth : Float get() = (getChildAt(0)?.width ?: width).toFloat()

    override fun onInterceptTouchEvent(ev : MotionEvent) : Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = ev.rawX
                startRawY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY
                if (!isDragging && abs(dx) > touchSlop && abs(dx) > abs(dy) && dx < 0) {
                    isDragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev : MotionEvent) : Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = ev.rawX
                startRawY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!isDragging) {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) && dx < 0) isDragging = true
                    return true
                }
                val tx = (ev.rawX - startRawX).coerceAtMost(0f)
                translationX = tx
                onDragProgress?.invoke((-tx / panelWidth).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    finishDrag(velocityTracker?.xVelocity ?: 0f)
                } else {
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) snapBack()
                else { velocityTracker?.recycle(); velocityTracker = null; isDragging = false }
            }
        }
        return isDragging
    }

    private fun finishDrag(velocityPx : Float) {
        velocityTracker?.recycle()
        velocityTracker = null
        isDragging = false
        if (translationX < -panelWidth * DISMISS_DRAG_FRACTION || velocityPx < DISMISS_FLING_VELOCITY) {
            animate()
                .translationX(-panelWidth)
                .setDuration(DISMISS_ANIMATION_DURATION)
                .withEndAction { onSwipeDismiss?.invoke() }
                .start()
        } else {
            snapBack()
        }
    }

    private fun snapBack() {
        isDragging = false
        onDragProgress?.invoke(0f)
        animate().translationX(0f).setDuration(SNAP_BACK_DURATION).start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        velocityTracker?.recycle()
        velocityTracker = null
        animate().cancel()
    }
}
