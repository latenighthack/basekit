package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DemoStore
import com.latenighthack.basekit.demo.GeneratedViewModelFactories
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject component that builds the demo's real ViewModels exactly as the TUI's generated
 * component does — resolving [DemoStore] from the graph and taking each screen's assisted parameter
 * (navigator / args / responder) at the call site. The factories (`homeFactory`, `detailFactory`,
 * `pickerFactory`) come from [GeneratedViewModelFactories], which basekit generates from the
 * `@ViewModelInject` implementations. The store is supplied per component so each test gets an
 * isolated backing store.
 */
@Component
abstract class DemoTestComponent(
    @get:Provides val store: DemoStore,
) : GeneratedViewModelFactories
