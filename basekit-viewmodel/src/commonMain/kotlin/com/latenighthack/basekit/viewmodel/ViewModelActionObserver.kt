package com.latenighthack.basekit.viewmodel

/** An invocation through an observed specification. Names are literals emitted by KSP, not reflection. */
public data class ViewModelActionEvent(
    val viewModelName: String,
    val actionName: String,
    /** The original implementation, available to explicit property providers; never serialized automatically. */
    val viewModel: Any,
)

/** Receives zero-argument actions before invocation. Mutators and internal self-calls are not observed. */
public fun interface ViewModelActionObserver {
    public fun onAction(event: ViewModelActionEvent)

    public companion object {
        public val NoOp: ViewModelActionObserver = ViewModelActionObserver { }
    }
}
