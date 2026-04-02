package com.framecaddy.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { FREE_DRAW, LINE }

    var touchEnabled = false

    private data class Element(
        val tool: Tool,
        val path: Path?,
        val start: PointF?,
        val end: PointF?,
        val color: Int
    )

    private val elements = mutableListOf<Element>()
    private var currentTool = Tool.FREE_DRAW
    private var currentColor = Color.WHITE
    private var activePath: Path? = null
    private var lineStart: PointF? = null
    private var linePreviewEnd: PointF? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f
    }
    private val dashEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled) return false
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> when (currentTool) {
                Tool.FREE_DRAW -> activePath = Path().also { it.moveTo(x, y) }
                Tool.LINE -> { lineStart = PointF(x, y); linePreviewEnd = PointF(x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                when (currentTool) {
                    Tool.FREE_DRAW -> activePath?.lineTo(x, y)
                    Tool.LINE -> linePreviewEnd = PointF(x, y)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (currentTool) {
                    Tool.FREE_DRAW -> activePath?.let {
                        elements.add(Element(Tool.FREE_DRAW, it, null, null, currentColor))
                        activePath = null
                    }
                    Tool.LINE -> lineStart?.let { s ->
                        elements.add(Element(Tool.LINE, null, s, PointF(x, y), currentColor))
                        lineStart = null; linePreviewEnd = null
                    }
                }
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        for (el in elements) {
            paint.color = el.color
            paint.pathEffect = null
            when (el.tool) {
                Tool.FREE_DRAW -> el.path?.let { canvas.drawPath(it, paint) }
                Tool.LINE -> {
                    val s = el.start ?: continue
                    val e = el.end ?: continue
                    canvas.drawLine(s.x, s.y, e.x, e.y, paint)
                }
            }
        }
        paint.color = currentColor
        paint.pathEffect = null
        activePath?.let { canvas.drawPath(it, paint) }
        val ls = lineStart; val pe = linePreviewEnd
        if (ls != null && pe != null) {
            paint.pathEffect = dashEffect
            canvas.drawLine(ls.x, ls.y, pe.x, pe.y, paint)
            paint.pathEffect = null
        }
    }

    fun undo() { if (elements.isNotEmpty()) { elements.removeAt(elements.lastIndex); invalidate() } }
    fun clear() { elements.clear(); activePath = null; lineStart = null; linePreviewEnd = null; invalidate() }
    fun setTool(tool: Tool) { currentTool = tool }
    fun setColor(color: Int) { currentColor = color }
}
