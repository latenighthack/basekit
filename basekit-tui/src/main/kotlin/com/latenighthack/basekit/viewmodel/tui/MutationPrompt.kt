package com.latenighthack.basekit.viewmodel.tui

/**
 * An in-progress argument entry for a mutation method the user triggered on a screen. The screen holds
 * one while the user supplies the value and routes keys to it: a boolean prompt takes `true`/`false`, a
 * text prompt accumulates typed characters until submitted. Submitting invokes the captured callback
 * (which launches the actual suspend mutation); the screen then clears the prompt.
 */
public class MutationPrompt private constructor(
    /** The mutation's method name, shown as the prompt's title. */
    public val label: String,
    /** True for a `true`/`false` choice, false for free text entry. */
    public val isBool: Boolean,
    private val onBool: (Boolean) -> Unit,
    private val onText: (String) -> Unit,
) {
    /** The text typed so far (text prompts only). */
    @Volatile
    public var text: String = ""
        private set

    /** Appends typed characters to the [text] buffer. */
    public fun type(chars: String) {
        text += chars
    }

    /** Removes the last typed character, if any. */
    public fun backspace() {
        if (text.isNotEmpty()) text = text.dropLast(1)
    }

    /** Invokes the mutation with the chosen boolean. */
    public fun submitBool(value: Boolean) {
        onBool(value)
    }

    /** Invokes the mutation with the entered [text]. */
    public fun submitText() {
        onText(text)
    }

    public companion object {
        /** A prompt collecting a `true`/`false` argument. */
        public fun bool(label: String, onSubmit: (Boolean) -> Unit): MutationPrompt =
            MutationPrompt(label, isBool = true, onBool = onSubmit, onText = {})

        /** A prompt collecting a typed-text argument. */
        public fun text(label: String, onSubmit: (String) -> Unit): MutationPrompt =
            MutationPrompt(label, isBool = false, onBool = {}, onText = onSubmit)
    }
}
