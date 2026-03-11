package com.taptotype

/**
 * Maps characters to USB HID keyboard scan codes.
 *
 * HID Keyboard report format (8 bytes):
 *   Byte 0: Modifier keys (bit flags)
 *   Byte 1: Reserved (0x00)
 *   Bytes 2-7: Key codes (up to 6 simultaneous keys)
 *
 * Modifier bit flags:
 *   0x01 = Left Ctrl
 *   0x02 = Left Shift
 *   0x04 = Left Alt
 *   0x08 = Left GUI (Windows key)
 *   0x10 = Right Ctrl
 *   0x20 = Right Shift
 *   0x40 = Right Alt
 *   0x80 = Right GUI
 */
object HidKeyMapper {

    /** Represents a single HID key event with modifier and key code. */
    data class HidKeyEvent(
        val modifier: Byte,
        val keyCode: Byte
    )

    // HID Usage IDs for keyboard keys (from USB HID Usage Tables)
    private const val KEY_A: Byte = 0x04
    private const val KEY_B: Byte = 0x05
    private const val KEY_C: Byte = 0x06
    private const val KEY_D: Byte = 0x07
    private const val KEY_E: Byte = 0x08
    private const val KEY_F: Byte = 0x09
    private const val KEY_G: Byte = 0x0A
    private const val KEY_H: Byte = 0x0B
    private const val KEY_I: Byte = 0x0C
    private const val KEY_J: Byte = 0x0D
    private const val KEY_K: Byte = 0x0E
    private const val KEY_L: Byte = 0x0F
    private const val KEY_M: Byte = 0x10
    private const val KEY_N: Byte = 0x11
    private const val KEY_O: Byte = 0x12
    private const val KEY_P: Byte = 0x13
    private const val KEY_Q: Byte = 0x14
    private const val KEY_R: Byte = 0x15
    private const val KEY_S: Byte = 0x16
    private const val KEY_T: Byte = 0x17
    private const val KEY_U: Byte = 0x18
    private const val KEY_V: Byte = 0x19
    private const val KEY_W: Byte = 0x1A
    private const val KEY_X: Byte = 0x1B
    private const val KEY_Y: Byte = 0x1C
    private const val KEY_Z: Byte = 0x1D

    private const val KEY_1: Byte = 0x1E
    private const val KEY_2: Byte = 0x1F
    private const val KEY_3: Byte = 0x20
    private const val KEY_4: Byte = 0x21
    private const val KEY_5: Byte = 0x22
    private const val KEY_6: Byte = 0x23
    private const val KEY_7: Byte = 0x24
    private const val KEY_8: Byte = 0x25
    private const val KEY_9: Byte = 0x26
    private const val KEY_0: Byte = 0x27

    private const val KEY_ENTER: Byte = 0x28
    private const val KEY_ESCAPE: Byte = 0x29
    private const val KEY_BACKSPACE: Byte = 0x2A
    private const val KEY_TAB: Byte = 0x2B
    private const val KEY_SPACE: Byte = 0x2C
    private const val KEY_MINUS: Byte = 0x2D          // - and _
    private const val KEY_EQUAL: Byte = 0x2E           // = and +
    private const val KEY_LEFT_BRACKET: Byte = 0x2F    // [ and {
    private const val KEY_RIGHT_BRACKET: Byte = 0x30   // ] and }
    private const val KEY_BACKSLASH: Byte = 0x31       // \ and |
    private const val KEY_SEMICOLON: Byte = 0x33       // ; and :
    private const val KEY_APOSTROPHE: Byte = 0x34      // ' and "
    private const val KEY_GRAVE: Byte = 0x35           // ` and ~
    private const val KEY_COMMA: Byte = 0x36           // , and <
    private const val KEY_PERIOD: Byte = 0x37          // . and >
    private const val KEY_SLASH: Byte = 0x38           // / and ?

    // Modifier constants
    private const val MOD_NONE: Byte = 0x00
    private const val MOD_LEFT_CTRL: Byte = 0x01
    private const val MOD_LEFT_SHIFT: Byte = 0x02

    /**
     * Character-to-HID mapping table.
     * Each entry maps a character to its (modifier, keyCode) pair.
     */
    private val charMap: Map<Char, HidKeyEvent> = mapOf(
        // Lowercase letters (no modifier)
        'a' to HidKeyEvent(MOD_NONE, KEY_A),
        'b' to HidKeyEvent(MOD_NONE, KEY_B),
        'c' to HidKeyEvent(MOD_NONE, KEY_C),
        'd' to HidKeyEvent(MOD_NONE, KEY_D),
        'e' to HidKeyEvent(MOD_NONE, KEY_E),
        'f' to HidKeyEvent(MOD_NONE, KEY_F),
        'g' to HidKeyEvent(MOD_NONE, KEY_G),
        'h' to HidKeyEvent(MOD_NONE, KEY_H),
        'i' to HidKeyEvent(MOD_NONE, KEY_I),
        'j' to HidKeyEvent(MOD_NONE, KEY_J),
        'k' to HidKeyEvent(MOD_NONE, KEY_K),
        'l' to HidKeyEvent(MOD_NONE, KEY_L),
        'm' to HidKeyEvent(MOD_NONE, KEY_M),
        'n' to HidKeyEvent(MOD_NONE, KEY_N),
        'o' to HidKeyEvent(MOD_NONE, KEY_O),
        'p' to HidKeyEvent(MOD_NONE, KEY_P),
        'q' to HidKeyEvent(MOD_NONE, KEY_Q),
        'r' to HidKeyEvent(MOD_NONE, KEY_R),
        's' to HidKeyEvent(MOD_NONE, KEY_S),
        't' to HidKeyEvent(MOD_NONE, KEY_T),
        'u' to HidKeyEvent(MOD_NONE, KEY_U),
        'v' to HidKeyEvent(MOD_NONE, KEY_V),
        'w' to HidKeyEvent(MOD_NONE, KEY_W),
        'x' to HidKeyEvent(MOD_NONE, KEY_X),
        'y' to HidKeyEvent(MOD_NONE, KEY_Y),
        'z' to HidKeyEvent(MOD_NONE, KEY_Z),

        // Uppercase letters (Left Shift + letter key)
        'A' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_A),
        'B' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_B),
        'C' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_C),
        'D' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_D),
        'E' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_E),
        'F' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_F),
        'G' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_G),
        'H' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_H),
        'I' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_I),
        'J' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_J),
        'K' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_K),
        'L' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_L),
        'M' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_M),
        'N' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_N),
        'O' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_O),
        'P' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_P),
        'Q' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_Q),
        'R' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_R),
        'S' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_S),
        'T' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_T),
        'U' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_U),
        'V' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_V),
        'W' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_W),
        'X' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_X),
        'Y' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_Y),
        'Z' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_Z),

        // Numbers (no modifier)
        '1' to HidKeyEvent(MOD_NONE, KEY_1),
        '2' to HidKeyEvent(MOD_NONE, KEY_2),
        '3' to HidKeyEvent(MOD_NONE, KEY_3),
        '4' to HidKeyEvent(MOD_NONE, KEY_4),
        '5' to HidKeyEvent(MOD_NONE, KEY_5),
        '6' to HidKeyEvent(MOD_NONE, KEY_6),
        '7' to HidKeyEvent(MOD_NONE, KEY_7),
        '8' to HidKeyEvent(MOD_NONE, KEY_8),
        '9' to HidKeyEvent(MOD_NONE, KEY_9),
        '0' to HidKeyEvent(MOD_NONE, KEY_0),

        // Symbols (Shift + number keys)
        '!' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_1),
        '@' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_2),
        '#' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_3),
        '$' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_4),
        '%' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_5),
        '^' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_6),
        '&' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_7),
        '*' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_8),
        '(' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_9),
        ')' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_0),

        // Special keys
        ' ' to HidKeyEvent(MOD_NONE, KEY_SPACE),
        '\n' to HidKeyEvent(MOD_NONE, KEY_ENTER),
        '\t' to HidKeyEvent(MOD_NONE, KEY_TAB),

        // Punctuation (no modifier)
        '-' to HidKeyEvent(MOD_NONE, KEY_MINUS),
        '=' to HidKeyEvent(MOD_NONE, KEY_EQUAL),
        '[' to HidKeyEvent(MOD_NONE, KEY_LEFT_BRACKET),
        ']' to HidKeyEvent(MOD_NONE, KEY_RIGHT_BRACKET),
        '\\' to HidKeyEvent(MOD_NONE, KEY_BACKSLASH),
        ';' to HidKeyEvent(MOD_NONE, KEY_SEMICOLON),
        '\'' to HidKeyEvent(MOD_NONE, KEY_APOSTROPHE),
        '`' to HidKeyEvent(MOD_NONE, KEY_GRAVE),
        ',' to HidKeyEvent(MOD_NONE, KEY_COMMA),
        '.' to HidKeyEvent(MOD_NONE, KEY_PERIOD),
        '/' to HidKeyEvent(MOD_NONE, KEY_SLASH),

        // Punctuation (Shift variants)
        '_' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_MINUS),
        '+' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_EQUAL),
        '{' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_LEFT_BRACKET),
        '}' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_RIGHT_BRACKET),
        '|' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_BACKSLASH),
        ':' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_SEMICOLON),
        '"' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_APOSTROPHE),
        '~' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_GRAVE),
        '<' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_COMMA),
        '>' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_PERIOD),
        '?' to HidKeyEvent(MOD_LEFT_SHIFT, KEY_SLASH)
    )

    /**
     * Maps a character to its HID key event (modifier + key code).
     * Returns null if the character is not supported.
     */
    fun charToHidKeyEvent(char: Char): HidKeyEvent? {
        return charMap[char]
    }

    /**
     * Creates an 8-byte HID keyboard report for the given key event.
     * Format: [modifier, 0x00, keyCode, 0, 0, 0, 0, 0]
     */
    fun createKeyDownReport(event: HidKeyEvent): ByteArray {
        return byteArrayOf(
            event.modifier,     // Modifier byte
            0x00,               // Reserved
            event.keyCode,      // Key code
            0x00, 0x00, 0x00, 0x00, 0x00  // Remaining key slots (unused)
        )
    }

    /**
     * Creates an 8-byte "all keys released" HID report.
     */
    fun createKeyUpReport(): ByteArray {
        return ByteArray(8) // All zeros = no keys pressed
    }

    /**
     * Creates a backspace key-down report.
     */
    fun createBackspaceReport(): ByteArray {
        return byteArrayOf(
            MOD_NONE,
            0x00,
            KEY_BACKSPACE,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * Creates an Enter key-down report.
     */
    fun createEnterReport(): ByteArray {
        return byteArrayOf(
            MOD_NONE,
            0x00,
            KEY_ENTER,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * Creates a Shift+Enter key-down report (line break in chat apps).
     */
    fun createShiftEnterReport(): ByteArray {
        return byteArrayOf(
            MOD_LEFT_SHIFT,
            0x00,
            KEY_ENTER,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * Creates a Ctrl+V key-down report (paste from clipboard).
     */
    fun createCtrlVReport(): ByteArray {
        return byteArrayOf(
            MOD_LEFT_CTRL,
            0x00,
            KEY_V,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }
}
