package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.RespondingDestination
import com.latenighthack.basekit.navigation.annotations.Destination

/** The value a [PickerScreen] hands back to whoever opened it (null when dismissed). */
data class PickResult(val selectedId: Int)

/**
 * A responding destination: because it is a [RespondingDestination], the generated `navigateToPicker`
 * method suspends its caller and returns `PickResult?`. [onItemSelected] responds with a value; closing
 * the screen resumes the caller with null.
 */
@Destination
interface PickerScreen : RespondingDestination<PickerScreen.Args, PickResult> {
    class Args : NavigatorArgs()

    suspend fun onItemSelected(id: Int)
}
