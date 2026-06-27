package com.keyflow.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.keyflow.core.ButtonState
import com.keyflow.data.OverlayPosition
import kotlin.math.roundToInt

class FloatingButtonView(
    context: Context,
    private val gestures: GestureInterpreter = GestureInterpreter(),
    private val onTapAction: () -> Unit = {},
    private val onHoldAction: () -> Unit = {},
    private val onShowInputMethodPicker: () -> Unit = {},
    private val onPositionChanged: (OverlayPosition) -> Unit = {},
) : View(context) {
    var renderedState: ButtonState = ButtonState.IDLE_NO_FIELD
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = READY_COLOR
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTap: Runnable? = null

    private var windowParams: WindowManager.LayoutParams? = null
    private var updateWindowLayout: ((WindowManager.LayoutParams) -> Unit)? = null
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var dragging = false

    init {
        render(ButtonState.IDLE_NO_FIELD)
        isClickable = true
        isFocusable = false
    }

    fun bindWindowLayout(
        params: WindowManager.LayoutParams,
        updateLayout: (WindowManager.LayoutParams) -> Unit,
    ) {
        windowParams = params
        updateWindowLayout = updateLayout
    }

    fun render(state: ButtonState) {
        renderedState = state
        fillPaint.color = when (state) {
            ButtonState.IDLE_READY -> READY_COLOR
            ButtonState.IDLE_NO_FIELD -> NO_FIELD_COLOR
            ButtonState.RECORDING -> RECORDING_COLOR
            ButtonState.SENDING -> SENDING_COLOR
            ButtonState.ERROR -> ERROR_COLOR
            ButtonState.PENDING_PASTE -> PENDING_COLOR
        }
        contentDescription = when (state) {
            ButtonState.IDLE_READY -> "KeyFlow ready"
            ButtonState.IDLE_NO_FIELD -> "KeyFlow no field"
            ButtonState.RECORDING -> "KeyFlow recording"
            ButtonState.SENDING -> "KeyFlow sending"
            ButtonState.ERROR -> "KeyFlow error"
            ButtonState.PENDING_PASTE -> "KeyFlow pending paste"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = BUTTON_SIZE_PX
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = width.coerceAtMost(height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
        canvas.drawText(labelFor(renderedState), width / 2f, height / 2f + 10f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingTap()
                parent?.requestDisallowInterceptTouchEvent(true)
                startRawX = event.rawX
                startRawY = event.rawY
                startWindowX = windowParams?.x ?: 0
                startWindowY = windowParams?.y ?: 0
                dragging = false
                gestures.onDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (gestures.onMove(dx, dy) == GestureEvent.DRAG_START) {
                    dragging = true
                }
                if (dragging) {
                    moveTo(startWindowX + dx.roundToInt(), startWindowY + dy.roundToInt())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val eventType = gestures.onUp()
                when (eventType) {
                    GestureEvent.TRIPLE_TAP -> {
                        cancelPendingTap()
                        onShowInputMethodPicker()
                    }
                    GestureEvent.HOLD -> onHoldAction()
                    GestureEvent.DRAG_END -> persistCurrentPosition()
                    GestureEvent.TAP -> if (!dragging) scheduleTap()
                    else -> Unit
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun scheduleTap() {
        val r = Runnable { onTapAction() }
        pendingTap = r
        handler.postDelayed(r, TAP_DELAY_MS)
    }

    private fun cancelPendingTap() {
        pendingTap?.let { handler.removeCallbacks(it) }
        pendingTap = null
    }

    private fun moveTo(x: Int, y: Int) {
        val params = windowParams ?: return
        params.gravity = params.gravity.takeIf { it != 0 } ?: (Gravity.TOP or Gravity.START)
        params.x = x
        params.y = y
        updateWindowLayout?.invoke(params)
    }

    private fun persistCurrentPosition() {
        val params = windowParams ?: return
        onPositionChanged(OverlayPosition(params.x, params.y))
    }

    private fun labelFor(state: ButtonState): String = when (state) {
        ButtonState.IDLE_READY -> "KF"
        ButtonState.IDLE_NO_FIELD -> "..."
        ButtonState.RECORDING -> "REC"
        ButtonState.SENDING -> "..."
        ButtonState.ERROR -> "!"
        ButtonState.PENDING_PASTE -> "PA"
    }

    private companion object {
        const val BUTTON_SIZE_PX = 144
        const val TAP_DELAY_MS = 250L
        val READY_COLOR = Color.rgb(46, 125, 50)
        val NO_FIELD_COLOR = Color.rgb(97, 97, 97)
        val RECORDING_COLOR = Color.rgb(198, 40, 40)
        val SENDING_COLOR = Color.rgb(21, 101, 192)
        val ERROR_COLOR = Color.rgb(230, 81, 0)
        val PENDING_COLOR = Color.rgb(106, 27, 154)
    }
}
