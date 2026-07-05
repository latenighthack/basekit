package com.latenighthack.basekit.navigation

import kotlin.reflect.KProperty

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
