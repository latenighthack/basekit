package com.latenighthack.basekit.fake.codegen

// What the generators write, with every type and expression already rendered as Kotlin source.
// Expressions read the fake's data from `fakeData`; list sizes and row choices name theirs `{data}`.

internal const val FAKE_PACKAGE = "com.latenighthack.basekit.fake"

/** A navigation target a fake is handed: the constructor parameter `detailTarget: DetailNavigationTarget?`. */
data class FakeTarget(
    /** The target's navigation name in upper camel case: `Detail`. */
    val navCap: String,
    /** `com.app.DetailNavigationTarget`. */
    val interfaceType: String,
) {
    val paramName: String get() = navCap.replaceFirstChar { it.lowercase() } + "Target"
}

/** One `@NavigateTo` edge of a function. */
data class FakeNavigation(
    val target: FakeTarget,
    /** The target spec's simple name, for the log when the fake has no navigator for it. */
    val targetName: String,
    /** The target's Args, built from `fakeData`; null when the target has no Args. */
    val args: String?,
    /** Args properties set from the State field of the same name. */
    val argsCopies: List<String> = emptyList(),
    /** The `<X>Source` entry when the target has more than one call site. */
    val source: String? = null,
    /** The target responds: the call suspends and returns its response. */
    val responding: Boolean = false,
)

/** An abstract function of the spec. */
data class FakeFunction(
    val name: String,
    val suspend: Boolean,
    /** `(note: kotlin.String)` */
    val parameters: List<Pair<String, String>> = emptyList(),
    /** Null for `Unit`. */
    val returnType: String? = null,
    val returnValue: String? = null,
    /** The State field the single parameter is written to. */
    val binds: String? = null,
    /** Called in turn, one per call. */
    val navigations: List<FakeNavigation> = emptyList(),
    /** Marked `@FakeResponds`. */
    val responds: Boolean = false,
) {
    /** A zero-argument function returning nothing: listed in `fakeActions`. */
    val isAction: Boolean get() = parameters.isEmpty() && returnType == null

    val effect: String
        get() = when {
            responds -> "RESPOND"
            navigations.isNotEmpty() -> "NAVIGATE"
            else -> "RECORD"
        }
}

/** How a fake makes one embedded view model (a row or a child): `FakeFeedItemViewModel(data, detailTarget = detailTarget)`. */
data class FakeEmbedded(
    val fakeClass: String,
    val targets: List<FakeTarget> = emptyList(),
    val responds: Boolean = false,
) {
    fun create(data: String): String = buildList {
        add(data)
        targets.forEach { add("${it.paramName} = ${it.paramName}") }
        if (responds) add("fakeResponder = fakeResponder")
    }.joinToString(", ", "$fakeClass(", ")")
}

/** A `@ViewModelList` property. */
data class FakeList(
    val property: String,
    val type: String,
    val elementType: String,
    /** The number of rows (per section), from `{data}`. */
    val size: String,
    val rows: List<FakeEmbedded>,
    /** An `IntArray` of row type weights from `{data}`, when there is more than one row type. */
    val weights: String? = null,
    /** Section header expressions for a sectioned list, built from `fakeData`; null for a flat list. */
    val sections: List<String>? = null,
    val headerType: String? = null,
    /** The `@FakePlaceholder` row, shown only when the list (or a section) has no other rows. */
    val placeholder: FakeEmbedded? = null,
)

data class FakeChild(val property: String, val type: String, val embedded: FakeEmbedded)

/** Any other abstract property: `override val name: Type = initializer`. */
data class FakeProperty(val name: String, val type: String, val mutable: Boolean, val initializer: String)

/** The `@Inject` constructor copied from the real implementation, and what it passes to the primary one. */
data class FakeInject(
    val parameters: List<Pair<String, String>>,
    val arguments: List<String>,
)

/** One fake class. */
data class FakeSpec(
    val specQualifiedName: String,
    val specSimpleName: String,
    val packageName: String,
    val className: String,
    val internal: Boolean = false,
    /** Set for a destination with Args. */
    val argsType: String? = null,
    val stateType: String,
    /** From `fakeData` and, for a destination with Args, `fakeArgs`. */
    val initialState: String,
    val targets: List<FakeTarget> = emptyList(),
    val responds: Boolean = false,
    val inject: FakeInject? = null,
    val functions: List<FakeFunction> = emptyList(),
    val lists: List<FakeList> = emptyList(),
    val children: List<FakeChild> = emptyList(),
    val properties: List<FakeProperty> = emptyList(),
    /** The `@RedirectsTo` targets, in declaration order. */
    val redirects: List<FakeNavigation> = emptyList(),
    /**
     * The index into [redirects] of the target to redirect to, from `fakeData`; -1 stays on this screen.
     * Null redirects to the first.
     */
    val redirectChoice: String? = null,
) {
    val qualifiedClassName: String get() = listOf(packageName, className).filter { it.isNotEmpty() }.joinToString(".")
}

/** A destination as the generated `FakeNavigator` opens it. */
data class FakeDestination(
    val navCap: String,
    val specType: String,
    val specSimpleName: String,
    val fakeClass: String,
    val argsType: String? = null,
    /** `com.app.FakeValues.detailViewModelArgs`, called with a `FakeData`. */
    val argsBuilder: String? = null,
    val targets: List<FakeTarget> = emptyList(),
    val responds: Boolean = false,
    val responseType: String? = null,
    /** The response built from `data`. */
    val responseValue: String? = null,
    val hosted: Boolean = false,
    /** Something navigates to it, so it has a `<X>NavigationTarget`. */
    val navigatedTo: Boolean = false,
    val hasSource: Boolean = false,
)
