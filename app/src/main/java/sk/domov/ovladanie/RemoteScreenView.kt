package sk.domov.ovladanie

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import sk.domov.ovladanie.pc.PcGestures
import sk.domov.ovladanie.pc.PcKeyboard
import sk.domov.ovladanie.pc.PcViewport
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Obraz PC na celú obrazovku: kreslí posledný obraz, rozpoznáva gestá (touchpad / priamy dotyk, rolovanie,
 * priblíženie) a berie text z klávesnice telefónu aj z fyzickej klávesnice. Logika gest a priblíženia je v
 * balíku pc (PcGestures, PcViewport, PcKeyboard), tu je len napojenie na Android.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class RemoteScreenView(context: Context) : View(context), PcGestures.Out {
    private var session: PcSession? = null
    private val viewport = PcViewport()
    private val density = resources.displayMetrics.density
    private val gestures = PcGestures(this, density, max(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 8f * density))
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastCursorVersion = -1
    private val tickRunnable = Runnable { gestures.tick(SystemClock.uptimeMillis()) }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = 2f * density
        ringPaint.color = Color.argb(235, 255, 193, 7)
        ringShadow.style = Paint.Style.STROKE
        ringShadow.strokeWidth = 4f * density
        ringShadow.color = Color.argb(140, 0, 0, 0)
    }

    fun attach(s: PcSession) {
        session = s
        s.onFrame = { postInvalidateOnAnimation() }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.onFrame = { postInvalidateOnAnimation() }
    }

    override fun onDetachedFromWindow() {
        session?.onFrame = null
        removeCallbacks(tickRunnable)
        gestures.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setView(w.toFloat(), h.toFloat())
    }

    fun resetZoom() {
        viewport.resetZoom()
        invalidate()
    }

    // ------------------------------------------------------------------ kreslenie

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        canvas.drawColor(Color.BLACK)
        synchronized(s.frameLock) {
            val bmp = s.frame ?: return
            viewport.setImage(bmp.width.toFloat(), bmp.height.toFloat())
            followCursor(s)
            val sc = viewport.scale()
            canvas.save()
            canvas.translate(viewport.left(), viewport.top())
            canvas.scale(sc, sc)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()
        }
        drawCursor(canvas, s)
    }

    /** V režime touchpadu pri priblížení drží kurzor v okne. */
    private fun followCursor(s: PcSession) {
        val v = s.cursorVersion
        if (v == lastCursorVersion) return
        lastCursorVersion = v
        if (s.entry.direct || !viewport.zoomed() || !s.cursorVisible) return
        val ix = s.cursorX16 / 65535f * viewport.imageWidth()
        val iy = s.cursorY16 / 65535f * viewport.imageHeight()
        viewport.ensureVisible(ix, iy, 72f * density)
    }

    private fun drawCursor(canvas: Canvas, s: PcSession) {
        if (s.entry.direct || !s.cursorVisible || !viewport.ready()) return
        val vx = viewport.toViewX(s.cursorX16 / 65535f * viewport.imageWidth())
        val vy = viewport.toViewY(s.cursorY16 / 65535f * viewport.imageHeight())
        canvas.drawCircle(vx, vy, 9f * density, ringShadow)
        canvas.drawCircle(vx, vy, 9f * density, ringPaint)
    }

    // ------------------------------------------------------------------ gestá

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = session ?: return false
        val en = s.entry
        gestures.direct = en.direct
        gestures.natural = en.natural
        gestures.sensitivity = when (en.sensitivity) {
            0 -> 0.6f
            2 -> 1.6f
            else -> 1f
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                gestures.down(event.eventTime, event.x, event.y)
                removeCallbacks(tickRunnable)
                postDelayed(tickRunnable, PcGestures.tickDelayMs())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    gestures.secondDown(event.eventTime, event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                } else {
                    gestures.extraDown()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    gestures.move(event.eventTime, 2, event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                } else {
                    gestures.move(event.eventTime, 1, event.getX(0), event.getY(0), 0f, 0f)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> gestures.pointerUp(event.eventTime, event.pointerCount - 1)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(tickRunnable)
                gestures.up(event.eventTime, event.x, event.y)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(tickRunnable)
                gestures.cancel()
            }
        }
        return true
    }

    // --- PcGestures.Out ---

    override fun moveRel(dx: Int, dy: Int) {
        session?.moveRel(dx, dy)
    }

    override fun pointAt(vx: Float, vy: Float): Boolean {
        val s = session ?: return false
        if (!viewport.inside(vx, vy)) return false
        val x16 = (viewport.toImageX(vx) / viewport.imageWidth() * 65535f).roundToInt().coerceIn(0, 65535)
        val y16 = (viewport.toImageY(vy) / viewport.imageHeight() * 65535f).roundToInt().coerceIn(0, 65535)
        s.moveAbs(x16, y16)
        return true
    }

    override fun button(button: Int, down: Boolean) {
        session?.button(button, down)
    }

    override fun scroll(dx: Int, dy: Int) {
        session?.scroll(dx, dy)
    }

    override fun pan(dvx: Float, dvy: Float) {
        viewport.panBy(dvx, dvy)
        invalidate()
    }

    override fun zoom(factor: Float, fvx: Float, fvy: Float) {
        viewport.zoomAt(factor, fvx, fvy)
        invalidate()
    }

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ------------------------------------------------------------------ klávesnica

    fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    fun showKeyboard() {
        requestFocus()
        post {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(this, 0)
        }
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        if (keyboardVisible()) hideKeyboard() else showKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // „viditeľné heslo“ + bez návrhov: klávesnica posiela znaky hneď a nerobí opravy, ktoré by na PC nesedeli
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return PcInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event) || super.onKeyUp(keyCode, event)

    /** Kláves z fyzickej klávesnice alebo z klávesnice telefónu; true = spracované. */
    internal fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL -> return false
        }
        val kb = s.keyboard
        val vk = PcKeyboard.vkForAndroid(keyCode)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (vk != 0 && PcKeyboard.isModifier(vk)) {
            s.key(vk, down)
            return true
        }
        if (!down) return vk != 0 || event.unicodeChar != 0
        val command = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        if (vk != 0 && (PcKeyboard.isFunctional(vk) || command)) {
            kb.press(vk)
            return true
        }
        val uc = event.unicodeChar
        if (uc != 0 && (uc and KeyCharacterMap.COMBINING_ACCENT) == 0) {
            kb.typeText(String(Character.toChars(uc)))
            return true
        }
        if (vk != 0) {
            kb.press(vk)
            return true
        }
        return false
    }

    internal fun keyboardOrNull(): PcKeyboard? = session?.keyboard
}

/**
 * Spojenie klávesnice telefónu s PC. Text, ktorý klávesnica ešte „skladá“ (návrhy, opravy), sa na PC píše hneď
 * a pri zmene sa na PC opraví klávesom Backspace – takže funguje aj klávesnica, ktorá si text ešte rozmýšľa.
 */
private class PcInputConnection(private val view: RemoteScreenView) : BaseInputConnection(view, false) {
    private var composing = ""

    private fun sync(newText: String) {
        val kb = view.keyboardOrNull() ?: return
        val e = PcKeyboard.diff(composing, newText)
        for (i in 0 until e.deleteCodePoints) kb.press(PcKeyboard.VK_BACK)
        if (e.insert.isNotEmpty()) kb.typeText(e.insert)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        sync(text?.toString().orEmpty())
        composing = ""
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val t = text?.toString().orEmpty()
        sync(t)
        composing = t
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val kb = view.keyboardOrNull() ?: return true
        for (i in 0 until beforeLength) kb.press(PcKeyboard.VK_BACK)
        for (i in 0 until afterLength) kb.press(PcKeyboard.VK_DELETE)
        if (composing.isNotEmpty() && beforeLength > 0) composing = composing.dropLast(minOf(beforeLength, composing.length))
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) view.handleKey(event.keyCode, event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        view.keyboardOrNull()?.press(PcKeyboard.VK_RETURN)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun setComposingRegion(start: Int, end: Int): Boolean = true

    override fun beginBatchEdit(): Boolean = true

    override fun endBatchEdit(): Boolean = true
}
