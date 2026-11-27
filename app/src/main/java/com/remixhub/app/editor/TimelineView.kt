package com.remixhub.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class ClipBar(
        val id: String,
        val name: String,
        val offsetMs: Int,
        val durationMs: Int,
        val muted: Boolean = false
    )

    var onClipMoved: ((String, Int) -> Unit)? = null
    var onClipSelected: ((String) -> Unit)? = null

    private var clips = listOf<ClipBar>()
    private var selectedId: String? = null
    private var originalDurationMs = 0
    private var timelineDurationMs = 30000
    private var playheadMs = 0

    private val density = resources.displayMetrics.density
    private val leftWidth = 92f * density
    private val topHeight = 36f * density
    private val rowHeight = 52f * density
    private val rowPadding = 8f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private var draggingId: String? = null
    private var dragStartX = 0f
    private var initialOffset = 0
    private var previewOffset = 0
    private var hasDragged = false

    init {
        minimumHeight = (160f * density).toInt()
        isClickable = true
        isFocusable = true
        contentDescription = "Audio timeline"
    }

    fun setTimeline(
        originalDurationMs: Int,
        clips: List<ClipBar>,
        selectedId: String?,
        durationMs: Int
    ) {
        this.originalDurationMs = originalDurationMs.coerceAtLeast(0)
        this.clips = clips
        this.selectedId = selectedId
        timelineDurationMs = maxOf(30000, durationMs)

        requestLayout()
        invalidate()
    }

    fun setPlayhead(positionMs: Int) {
        playheadMs = positionMs.coerceAtLeast(0)
        invalidate()
    }

    private fun timelineWidth(): Float =
        (width - leftWidth - rowPadding).coerceAtLeast(1f)

    private fun xForTime(ms: Int): Float =
        leftWidth + ms.toFloat() /
            timelineDurationMs * timelineWidth()

    private fun timeForDistance(dx: Float): Int =
        (dx / timelineWidth() *
            timelineDurationMs).roundToInt()

    private fun clipRect(index: Int, clip: ClipBar): RectF {
        val offset = if (clip.id == draggingId) {
            previewOffset
        } else {
            clip.offsetMs
        }

        val top = topHeight +
            (index + 1) * rowHeight + rowPadding

        val left = xForTime(offset)
        val right = xForTime(
            (offset + clip.durationMs).coerceAtMost(
                timelineDurationMs
            )
        )

        return RectF(
            left,
            top,
            maxOf(left + 6f * density, right),
            top + rowHeight - 2 * rowPadding
        )
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val height = (
            topHeight +
            (clips.size + 1) * rowHeight +
            rowPadding
        ).toInt()

        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthEnd = width.toFloat()

        canvas.drawColor(Color.WHITE)

        // Timeline scale and vertical grid.
        for (i in 0..6) {
            val time = timelineDurationMs * i / 6
            val x = xForTime(time)

            paint.color = Color.LTGRAY
            paint.strokeWidth = density

            canvas.drawLine(
                x, topHeight - 6f * density,
                x, height.toFloat(), paint
            )

            paint.color = Color.BLACK
            paint.textSize = 10f *
                resources.displayMetrics.scaledDensity

            canvas.drawText(
                "${time / 1000}s",
                x + 2f * density,
                topHeight - 14f * density,
                paint
            )
        }

        // Original track row.
        drawTrackBackground(canvas, 0)

        paint.color = Color.BLACK
        paint.textSize = 12f *
            resources.displayMetrics.scaledDensity

        canvas.drawText(
            "Original",
            4f * density,
            topHeight + 30f * density,
            paint
        )

        if (originalDurationMs > 0) {
            val top = topHeight + rowPadding

            paint.color = Color.DKGRAY

            canvas.drawRoundRect(
                RectF(
                    leftWidth,
                    top,
                    xForTime(originalDurationMs)
                        .coerceAtMost(widthEnd),
                    top + rowHeight - 2 * rowPadding
                ),
                5f * density,
                5f * density,
                paint
            )
        }

        // Recording rows.
        clips.forEachIndexed { index, clip ->
            drawTrackBackground(canvas, index + 1)

            val top = topHeight +
                (index + 1) * rowHeight

            paint.color = Color.BLACK
            paint.textSize = 11f *
                resources.displayMetrics.scaledDensity

            canvas.drawText(
                clip.name.take(11),
                4f * density,
                top + 30f * density,
                paint
            )

            val rect = clipRect(index, clip)

            paint.color = when {
                clip.muted -> Color.LTGRAY
                clip.id == selectedId -> Color.BLACK
                else -> Color.GRAY
            }

            canvas.drawRoundRect(
                rect,
                5f * density,
                5f * density,
                paint
            )

            paint.color = if (
                clip.id == selectedId && !clip.muted
            ) Color.WHITE else Color.BLACK

            paint.textSize = 10f *
                resources.displayMetrics.scaledDensity

            if (rect.width() > 45f * density) {
                canvas.drawText(
                    "${(if (clip.id == draggingId)
                        previewOffset else clip.offsetMs) / 1000.0}s",
                    rect.left + 5f * density,
                    rect.centerY() + 4f * density,
                    paint
                )
            }
        }

        // Global playhead.
        val playheadX = xForTime(playheadMs)
            .coerceAtMost(widthEnd)

        paint.color = Color.BLACK
        paint.strokeWidth = 2f * density

        canvas.drawLine(
            playheadX,
            topHeight - 7f * density,
            playheadX,
            height.toFloat(),
            paint
        )
    }

    private fun drawTrackBackground(
        canvas: Canvas,
        row: Int
    ) {
        val top = topHeight +
            row * rowHeight + rowPadding

        paint.color = Color.rgb(237, 237, 237)

        canvas.drawRoundRect(
            RectF(
                leftWidth,
                top,
                width.toFloat() - rowPadding,
                top + rowHeight - 2 * rowPadding
            ),
            5f * density,
            5f * density,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val row = (
                    (event.y - topHeight) / rowHeight
                ).toInt() - 1

                if (row !in clips.indices) return false

                val clip = clips[row]
                val rect = clipRect(row, clip)

                if (!rect.contains(event.x, event.y)) {
                    return false
                }

                draggingId = clip.id
                dragStartX = event.x
                initialOffset = clip.offsetMs
                previewOffset = clip.offsetMs
                hasDragged = false

                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingId == null) return false

                val delta = event.x - dragStartX

                if (abs(delta) > 5f * density) {
                    hasDragged = true
                }

                val newOffset = initialOffset +
                    timeForDistance(delta)

                // Snap to 100 ms.
                previewOffset = (
                    newOffset / 100f
                ).roundToInt() * 100

                previewOffset =
                    previewOffset.coerceIn(0, 30000)

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = draggingId ?: return false
                draggingId = null

                parent?.requestDisallowInterceptTouchEvent(false)

                if (hasDragged) {
                    if (previewOffset != initialOffset) {
                        onClipMoved?.invoke(id, previewOffset)
                    }
                } else {
                    performClick()
                    onClipSelected?.invoke(id)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                draggingId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}