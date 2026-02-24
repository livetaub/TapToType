package com.taptotype

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Custom EditText that intercepts backspace even when the field is empty.
 * The soft keyboard calls deleteSurroundingText() instead of sending a KeyEvent,
 * so a normal EditText won't notify our TextWatcher when there's nothing to delete.
 */
class HidEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Called when backspace is pressed but the field is empty. */
    var onEmptyBackspace: (() -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(baseConnection, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // If the field is empty and backspace is pressed, notify the listener
                if (beforeLength == 1 && afterLength == 0 && text?.isEmpty() == true) {
                    onEmptyBackspace?.invoke()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }
}
