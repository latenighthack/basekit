@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package com.latenighthack.basekit.demo

@kotlin.js.JsExport
fun createBindingProbe(): BindingProbeViewModelReactRef = RealBindingProbeViewModel().reactReference()
@kotlin.js.JsExport
fun createBindingChild(): BindingChildViewModelReactRef = RealBindingChildViewModel("browser").reactReference()

/** Browser acceptance controls stay outside the shared binding contract. */
@kotlin.js.JsExport
class BrowserBindingProbe {
    private val model = RealBindingProbeViewModel()
    val reference = model.reactReference()
    val activeStateCollectors: Int get() = model.activeStateCollectors
    val startedActions: Int get() = model.startedActions
    val cancelledActions: Int get() = model.cancelledActions
    fun staticRows(title: String) = model.replaceStaticRows(title)
    fun populate() = model.replaceRows()
    fun empty() = model.clearRows()
}
