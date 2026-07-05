package com.latenighthack.basekit.navigation

import android.os.Bundle
import java.io.Serializable
import kotlin.reflect.KProperty

/**
 * Android backs navigation args with a [Bundle] so they survive the Activity/process serialization
 * boundary. The [bundle] can be attached to an Intent by the platform navigator.
 */
public actual open class NavigatorArgs {
    private val internalBundle: Bundle = Bundle()

    public val bundle: Bundle
        get() = internalBundle

    protected inner class StringBundledProperty : StoredProperty<String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            internalBundle.getString(property.name)!!

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String): Unit =
            internalBundle.putString(property.name, value)
    }

    protected inner class ByteArrayBundledProperty : StoredProperty<ByteArray> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray =
            internalBundle.getByteArray(property.name)!!

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: ByteArray): Unit =
            internalBundle.putByteArray(property.name, value)
    }

    protected inner class IntBundledProperty : StoredProperty<Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
            internalBundle.getInt(property.name)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int): Unit =
            internalBundle.putInt(property.name, value)
    }

    protected inner class BooleanBundledProperty : StoredProperty<Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            internalBundle.getBoolean(property.name)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean): Unit =
            internalBundle.putBoolean(property.name, value)
    }

    protected inner class SerializableBundledProperty<T> : StoredProperty<T> {
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T =
            internalBundle.getSerializable(property.name) as T

        @Suppress("UNCHECKED_CAST")
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T): Unit =
            internalBundle.putSerializable(property.name, value as Serializable)
    }

    @Suppress("UNCHECKED_CAST")
    protected actual inline fun <reified T> storedProperty(): StoredProperty<T> {
        return when (T::class) {
            String::class -> StringBundledProperty()
            ByteArray::class -> ByteArrayBundledProperty()
            Int::class -> IntBundledProperty()
            Boolean::class -> BooleanBundledProperty()
            else -> SerializableBundledProperty<T>()
        } as StoredProperty<T>
    }
}
