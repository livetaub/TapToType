package com.taptotype

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Custom EditText that:
 * 1. Intercepts backspace even when the field is empty.
 * 2. Can lock the cursor to the end of text (for live typing mode).
 */
class HidEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Called when backspace is pressed but the field is empty. */
    var onEmptyBackspace: (() -> Unit)? = null

    /** When true, cursor is always forced to the end of text. */
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

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (lockCursorToEnd) {
            val len = text?.length ?: 0
            if (selStart != len || selEnd != len) {
                post { setSelection(len) }
            }
        }
    }
}
