package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.annotations.ViewModelModule
import me.tatarka.inject.annotations.Provides

/** An app dependency an injected ViewModel pulls from the kotlin-inject graph. */
interface CounterRepository {
    fun initialCount(): Int
}

private class RealCounterRepository : CounterRepository {
    override fun initialCount(): Int = 42
}

/**
 * App-supplied kotlin-inject bindings. `@ViewModelModule` makes the generated `GeneratedTuiComponent`
 * include this interface, so `@ViewModelInject` ViewModels can depend on [CounterRepository] without
 * anyone hand-writing the component.
 */
@ViewModelModule
interface DemoModule {
    @Provides
    fun counterRepository(): CounterRepository = RealCounterRepository()
}
