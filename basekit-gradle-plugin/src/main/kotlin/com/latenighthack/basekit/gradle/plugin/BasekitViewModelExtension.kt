package com.latenighthack.basekit.gradle.plugin

import org.gradle.api.provider.Property

/**
 * What the ViewModel processor generates for a module (see `com.latenighthack.basekit.viewmodel`). Each
 * switch left unset keeps the processor's default, also settable as its KSP argument.
 *
 * A binding switched off for the module is still generated for a spec annotated
 * `@ViewModelBindings(ViewModelBinding.KVO, …)`, and for that spec's list rows and child view models.
 */
abstract class BasekitViewModelExtension {
    /** SwiftUI `Observable{Spec}` wrappers and `BasekitDestinationModels.swift` (`basekit.viewmodel.swiftui`, default on). */
    abstract val swiftUi: Property<Boolean>

    /** UIKit/AppKit `Kvo{Spec}` wrappers (`basekit.viewmodel.kvo`, default on). */
    abstract val kvo: Property<Boolean>

    /** Compose bindings and destination models (`basekit.viewmodel.compose`, default off). */
    abstract val compose: Property<Boolean>

    /** Android view `Abstract{Spec}Activity` base classes (`basekit.viewmodel.androidView`, default on). */
    abstract val androidView: Property<Boolean>

    /** React `use{Spec}` hooks and their `{Spec}ReactRef` (`basekit.viewmodel.reactHooks`, default on). */
    abstract val reactHooks: Property<Boolean>

    /**
     * Whether `Observing{Spec}` names the State properties each emission changed, for
     * `ViewModelEvent.StateChanged.changedProperties` (`basekit.viewmodel.stateDiff`, default on).
     */
    abstract val stateDiff: Property<Boolean>

    /**
     * Whether `Observing{Spec}` implements `ViewModelFailureHandler`, forwarding to its delegate
     * (`basekit.viewmodel.failureForwarding`, default on). Off, bindings still reach the delegate's handler.
     */
    abstract val failureForwarding: Property<Boolean>
}
