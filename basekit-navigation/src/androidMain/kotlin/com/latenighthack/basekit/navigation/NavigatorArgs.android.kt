package com.latenighthack.basekit.navigation

import android.os.Bundle
import java.io.Serializable
import kotlin.reflect.KProperty

/**
 * Android backs navigation args with a [Bundle] so they survive the Activity/process serialization
 * boundary. The [bundle] can be attached to an Intent by the platform navigator.
 */
public actual open class NavigatorArgs actual constructor() {
    private val internalBundle: Bundle = Bundle()

    public val bundle: Bundle
        get() = internalBundle

    /** Fails with the argument's name when it was never set, as the in-memory actuals do. */
    private fun requireSet(property: KProperty<*>) {
        check(internalBundle.containsKey(property.name)) { "Navigation argument ${property.name} was never set" }
    }

    protected inner class StringBundledProperty : StoredProperty<String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String {
            requireSet(property)
            return internalBundle.getString(property.name)!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String): Unit =
            internalBundle.putString(property.name, value)
    }

    protected inner class ByteArrayBundledProperty : StoredProperty<ByteArray> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray {
            requireSet(property)
            return internalBundle.getByteArray(property.name)!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: ByteArray): Unit =
            internalBundle.putByteArray(property.name, value)
    }

    protected inner class IntBundledProperty : StoredProperty<Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            requireSet(property)
            return internalBundle.getInt(property.name)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int): Unit =
            internalBundle.putInt(property.name, value)
    }

    protected inner class BooleanBundledProperty : StoredProperty<Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            requireSet(property)
            return internalBundle.getBoolean(property.name)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean): Unit =
            internalBundle.putBoolean(property.name, value)
    }

    /**
     * A nullable argument whose values [present] stores. Null is stored as a key with a null value, so
     * "set to null" reads back null and "never set" still fails, as in [SerializableBundledProperty].
     */
    protected inner class NullableBundledProperty<T : Any>(private val present: StoredProperty<T>) : StoredProperty<T?> {
        @Suppress("DEPRECATION")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T? {
            requireSet(property)
            return if (internalBundle.get(property.name) == null) null else present.getValue(thisRef, property)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
            if (value == null) internalBundle.putString(property.name, null) else present.setValue(thisRef, property, value)
        }
    }

    protected inner class SerializableBundledProperty<T> : StoredProperty<T> {
        // A nullable argument may hold null, so whether it was set is the key's presence, not its value.
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            requireSet(property)
            return internalBundle.getSerializable(property.name) as T
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T): Unit =
            internalBundle.putSerializable(property.name, value as Serializable?)
    }

    // `T::class` is the same for `String` and `String?`; `null is T` tells them apart.
    @Suppress("UNCHECKED_CAST")
    protected actual inline fun <reified T> storedProperty(): StoredProperty<T> {
        val present: StoredProperty<*> = when (T::class) {
            String::class -> StringBundledProperty()
            ByteArray::class -> ByteArrayBundledProperty()
            Int::class -> IntBundledProperty()
            Boolean::class -> BooleanBundledProperty()
            else -> return SerializableBundledProperty<T>()
        }
        return (if (null is T) NullableBundledProperty(present as StoredProperty<Any>) else present) as StoredProperty<T>
    }
}
