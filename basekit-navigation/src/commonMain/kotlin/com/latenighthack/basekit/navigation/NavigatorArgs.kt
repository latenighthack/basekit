package com.latenighthack.basekit.navigation

import kotlin.reflect.KProperty

/** A read/write delegate backing a single navigation argument. */
public interface StoredProperty<T> {
    public operator fun getValue(thisRef: Any?, property: KProperty<*>): T

    public operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T)
}

/**
 * Base type for a destination's navigation arguments. Subclasses declare each argument as a
 * delegated property backed by [storedProperty], letting the platform decide how the value is
 * stored and carried across the navigation boundary (an Android [android.os.Bundle], or plain
 * in-memory storage on iOS/JVM/JS where there is no serialization boundary).
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect open class NavigatorArgs constructor() {
    protected inline fun <reified T> storedProperty(): StoredProperty<T>
}
