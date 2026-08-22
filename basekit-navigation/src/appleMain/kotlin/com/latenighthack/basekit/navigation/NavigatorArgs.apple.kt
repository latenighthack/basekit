package com.latenighthack.basekit.navigation

import kotlin.reflect.KProperty

/**
 * Apple platforms have no serialization boundary between destinations, so navigation args are held
 * in memory. This lives in `appleMain` rather than `iosMain` so the single actual covers iOS and
 * macOS (and any future tvOS/watchOS target) — the implementation touches no platform API at all.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual open class NavigatorArgs actual constructor() {
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
