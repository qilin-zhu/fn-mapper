package com.example.fnmapper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/**
 * 支持页面对齐（snap）的 HorizontalScrollView：
 * 手指抬起或惯性滑动结束后，自动滚到最近的一整页，不会停在两页中间。
 */
class SnapHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** 轮询检测滑动是否结束，结束后对齐到最近一页 */
    private val snapRunnable = object : Runnable {
        private var lastX = Int.MIN_VALUE
        override fun run() {
            val x = scrollX
            if (x != lastX) { // 仍在滚动（手势拖动或惯性滑动）
                lastX = x
                postDelayed(this, 50)
                return
            }
            snapToNearestPage()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> removeCallbacks(snapRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // 稍等惯性滑动启动，再开始轮询直到停止
                postDelayed(snapRunnable, 80)
        }
        return handled
    }

    private fun snapToNearestPage() {
        val pageWidth = width
        if (pageWidth <= 0) return
        val content = getChildAt(0) ?: return
        val maxPage = ((content.width / pageWidth) - 1).coerceAtLeast(0)
        val page = Math.round(scrollX.toFloat() / pageWidth).coerceIn(0, maxPage)
        val target = page * pageWidth
        if (scrollX != target) smoothScrollTo(target, 0)
    }
}
