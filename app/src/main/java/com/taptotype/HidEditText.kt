package com.taptotype

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Custom EditText that:
 * 1. Intercepts backspace even when the field is empty.
 * 2. Can block touch-based cursor movement (for live typing mode).
 */
class HidEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Called when backspace is pressed but the field is empty. */
    var onEmptyBackspace: (() -> Unit)? = null

    /** When true, touches won't move the cursor — it stays at the end. */
    var lockCursorToEnd: Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(baseConnection, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && text?.isEmpty() == true) {
                    onEmptyBackspace?.invoke()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (lockCursorToEnd) {
            // Let the touch through for focus/keyboard but don't let it move the cursor
            if (event?.action == MotionEvent.ACTION_UP) {
                requestFocus()
                // Keep cursor at the end
                text?.let { setSelection(it.length) }
                return true
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
