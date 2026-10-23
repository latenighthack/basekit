@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.posthog.JavaScriptPostHogMetrics
import com.latenighthack.basekit.navigation.posthog.asCollector
import com.latenighthack.basekit.viewmodel.ViewModelActionObserver

@kotlin.js.JsExport
fun createBindingProbe(): BindingProbeViewModelReactRef = RealBindingProbeViewModel().reactReference()
@kotlin.js.JsExport
fun createBindingChild(): BindingChildViewModelReactRef = RealBindingChildViewModel("browser").reactReference()

/** Browser acceptance controls stay outside the shared binding contract. */
@kotlin.js.JsExport
class BrowserBindingProbe(metrics: JavaScriptPostHogMetrics? = null) {
    private val model = RealBindingProbeViewModel()
    private val collector = metrics?.asCollector()
    val reference = model.observingActions(collector ?: ViewModelActionObserver.NoOp).reactReference()
    val activeStateCollectors: Int get() = model.activeStateCollectors
    val startedActions: Int get() = model.startedActions
    val cancelledActions: Int get() = model.cancelledActions
    fun staticRows(title: String) = model.replaceStaticRows(title)
    fun populate() = model.replaceRows()
    fun empty() = model.clearRows()
    fun failState(message: String) = model.failState(message)
    fun failRows(message: String) = model.failRows(message)
    val handledFailures: Array<String> get() = model.handledFailures.toTypedArray()
    fun navigate() {
        collector?.onNavigation(NavigationEvent.NavigatedTo(NavigationScreen.DETAIL, DetailViewModel::class, null,
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, null))
        collector?.onNavigation(NavigationEvent.Closed(NavigationScreen.DETAIL, null))
        collector?.onNavigation(NavigationEvent.Responded(NavigationScreen.DETAIL, null))
    }
}

/** A probe whose view model, its children and its rows report to a recording ViewModelObserver. */
@kotlin.js.JsExport
class BrowserObservationProbe {
    private val model = RealBindingProbeViewModel()
    private val recorded = mutableListOf<String>()
    private val observer = com.latenighthack.basekit.viewmodel.ViewModelObserver { event ->
        val kind = when (event) {
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Started -> "Started"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Completed -> "Completed"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Failed -> "Failed"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Cancelled -> "Cancelled"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Attached -> "Attached"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.Detached -> "Detached"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.FirstEmission -> "FirstEmission"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.StateChanged -> "StateChanged"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.ListDelta -> "ListDelta"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.SectionedListDelta -> "SectionedListDelta"
            is com.latenighthack.basekit.viewmodel.ViewModelEvent.ListPaging -> "ListPaging"
        }
        recorded += "$kind ${event.operation.kind} ${event.operation.owner.substringAfterLast('.')}.${event.operation.name}"
    }
    val reference = model.reactReference(observer)
    /** Each event as `Kind KIND Owner.name`. */
    val lines: Array<String> get() = recorded.toTypedArray()
    val activeStateCollectors: Int get() = model.activeStateCollectors
    fun populate() = model.replaceRows()
}
