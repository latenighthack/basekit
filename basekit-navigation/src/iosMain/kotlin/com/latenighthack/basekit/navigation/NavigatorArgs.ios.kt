package com.latenighthack.basekit.navigation

import kotlin.reflect.KProperty

/**
 * iOS has no serialization boundary between destinations, so navigation args are held in memory.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual open class NavigatorArgs {
    protected class InMemoryStoredProperty<T> : StoredProperty<T> {
        private var storedValue: T? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = storedValue!!

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            storedValue = value
        }
    }

    protected actual inline fun <reified T> storedProperty(): StoredProperty<T> =
        InMemoryStoredProperty()
}
