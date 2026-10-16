package com.latenighthack.basekit.viewmodel.compose

import com.latenighthack.basekit.navigation.NavigatorArgs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Android's bundled navigation arguments hold null for a nullable argument, as the other targets do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class AndroidNavigatorArgsTest {
    enum class Context { APPROVAL }

    class Args : NavigatorArgs() {
        var context: Context? by storedProperty()
        var required: Context by storedProperty()
    }

    @Test fun aNullableArgumentHoldsNullOrAValue() {
        val args = Args()
        args.context = null
        assertNull(args.context)
        args.context = Context.APPROVAL
        assertEquals(Context.APPROVAL, args.context)
    }

    @Test fun anArgumentNeverSetFails() {
        assertFailsWith<IllegalStateException> { Args().required }
    }
}
