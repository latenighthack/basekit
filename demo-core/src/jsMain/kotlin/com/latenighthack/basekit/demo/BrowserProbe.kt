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
    fun navigate() {
        collector?.onNavigation(NavigationEvent.NavigatedTo(NavigationScreen.DETAIL, DetailViewModel::class, null,
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, null))
        collector?.onNavigation(NavigationEvent.Closed(NavigationScreen.DETAIL, null))
        collector?.onNavigation(NavigationEvent.Responded(NavigationScreen.DETAIL, null))
    }
}
