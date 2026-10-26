package com.latenighthack.basekit.fake

import kotlin.reflect.KClass

/** Marks a class basekit-fake-ksp generated as the fake implementation of [spec]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class GeneratedFake(val spec: KClass<*>)

/** Answers a responding destination with a value made from [fake]; the fake of the destination builds it. */
public fun interface FakeResponder {
    public fun respond(fake: FakeData)
}

/** A zero-argument action of a fake, and what calling it does. */
public class FakeAction(
    public val name: String,
    public val effect: Effect,
    public val invoke: suspend () -> Unit,
) {
    public enum class Effect {
        /** Records the call only. */
        RECORD,

        /** Opens another screen. */
        NAVIGATE,

        /** Answers the responding destination it is shown in, which closes it. */
        RESPOND,
    }

    override fun toString(): String = "$name ($effect)"
}

/** What every generated fake implements besides its spec: what [walkFakeGraph] calls. */
public interface FakeViewModel {
    /** The fake's data: its path names it in the log. */
    public val fakeData: FakeData

    /** The spec's zero-argument actions. */
    public val fakeActions: List<FakeAction>

    /** The fakes this one shows: its children and the rows it created for its lists. */
    public val fakeEmbedded: List<FakeViewModel>

    /**
     * For the fake of a destination with `@RedirectsTo` edges: redirects to its target, once. Collecting
     * `state` calls it, as a real redirecting screen redirects while its state is collected; the
     * generated `FakeNavigator` calls it when it opens the screen. Does nothing for other fakes.
     */
    public fun fakeRedirect() {}
}

/**
 * A navigator that replaces the current screen, instead of opening a new one, for the navigation [block]
 * makes. The generated `FakeNavigator` is one.
 */
public interface FakeRedirector {
    public fun redirecting(block: () -> Unit)
}

/**
 * Runs [block], which navigates with [navigator], as a redirect: through [FakeRedirector.redirecting]
 * when [navigator] is one, else as it is.
 */
public fun fakeRedirecting(navigator: Any, block: () -> Unit) {
    if (navigator is FakeRedirector) navigator.redirecting(block) else block()
}
