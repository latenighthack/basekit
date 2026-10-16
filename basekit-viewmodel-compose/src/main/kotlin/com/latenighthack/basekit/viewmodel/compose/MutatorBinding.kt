package com.latenighthack.basekit.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job

/**
 * A two-way binding for a state property its view model sets through a mutator, as the Swift
 * bindings' `{noun}Binding`: [value] reads the latest state, [onValueChange] calls the mutator. A
 * bound control such as a text field passes both on.
 */
public class MutatorBinding<T>(
    public val value: T,
    public val onValueChange: (T) -> Unit,
)

/**
 * Binds [current], the state's value, to [mutate]. A change echoes at once, so a bound control stays
 * responsive, and the state that [mutate] produces reconciles it a moment later, as on Apple
 * platforms.
 */
@Composable
public fun <T> rememberMutatorBinding(
    current: T,
    launchAction: (suspend () -> Unit) -> Job,
    mutate: suspend (T) -> Unit,
): MutatorBinding<T> {
    val echo = remember { Echo(current) }
    if (echo.lastState != current) {
        echo.lastState = current
        echo.value.value = current
    }
    return MutatorBinding(echo.value.value) { newValue ->
        echo.value.value = newValue
        launchAction { mutate(newValue) }
    }
}

private class Echo<T>(initial: T) {
    var lastState: T = initial
    val value: MutableState<T> = mutableStateOf(initial)
}
