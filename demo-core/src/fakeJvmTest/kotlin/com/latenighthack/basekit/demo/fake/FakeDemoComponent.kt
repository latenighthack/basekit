package com.latenighthack.basekit.demo.fake

import com.latenighthack.basekit.demo.DemoStore
import com.latenighthack.basekit.demo.GeneratedViewModelFactories
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/** The same component as the real tests' DemoTestComponent: with fakes on, it builds the fakes. */
@Component
abstract class FakeDemoComponent(
    @get:Provides val store: DemoStore,
) : GeneratedViewModelFactories
