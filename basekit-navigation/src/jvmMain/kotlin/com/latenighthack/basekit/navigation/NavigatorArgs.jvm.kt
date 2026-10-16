package com.latenighthack.basekit.navigation

import kotlin.reflect.KProperty

public actual open class NavigatorArgs actual constructor() {
    protected class InMemoryStoredProperty<T> : StoredProperty<T> {
        private var storedValue: T? = null
        private var isSet = false

        // A nullable argument may hold null, so whether it was set is tracked apart from its value.
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            check(isSet) { "Navigation argument ${property.name} was never set" }
            return storedValue as T
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            storedValue = value
            isSet = true
        }
    }

    protected actual inline fun <reified T> storedProperty(): StoredProperty<T> =
        InMemoryStoredProperty()
}
