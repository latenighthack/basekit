package com.latenighthack.basekit.catalog.tool

/** A `catalog/v1` document as read from YAML. See docs/guides/catalog.md for the format. */
data class CatalogDocument(
    val screens: List<CatalogVm>,
    val components: List<CatalogVm>,
    val types: List<CatalogTypeDef>,
    /** Keys the reader does not know, by YAML path; they are ignored. */
    val warnings: List<String> = emptyList(),
)

/** A `name: Type` pair; for `children` the type is a component name. A State field may carry [fake]. */
data class Field(val name: String, val type: String, val fake: FakeSettings? = null)

/**
 * The `fake:` key of a list or a State field: what basekit's generated fakes show there, written as
 * fake annotations. [base] applies to every data set; [dataSets] to one data set (and those based on
 * it) each.
 */
data class FakeSettings(
    val base: FakeValues = FakeValues(),
    /** A list's placeholder row (`@FakePlaceholder`): shown only when the list has no other rows. */
    val placeholder: String? = null,
    val dataSets: List<Pair<String, FakeValues>> = emptyList(),
)

/** The fake values one data set gets. Lists use [size], [min], [max] and [rows]; State fields the rest. */
data class FakeValues(
    val size: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
    val rows: List<String> = emptyList(),
    val text: FakeTextValue? = null,
    val number: Pair<Double, Double>? = null,
    val nullFrequency: Double? = null,
    val cases: List<String> = emptyList(),
    val value: String? = null,
) {
    val isEmpty: Boolean get() = this == FakeValues()
}

/** `text:`: a `FakeTextKind` name, a length limit, or fixed values. */
data class FakeTextValue(
    val kind: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val values: List<String> = emptyList(),
)

data class Navigation(
    val args: List<Field>,
    val route: String?,
    val routeArgs: List<String>,
    val response: String?,
    /** Other paths that open the screen (`route_aliases`, `@Route(aliases)`). */
    val routeAliases: List<String> = emptyList(),
)

data class Action(val name: String, val navigatesTo: List<String>)

data class Mutation(val name: String, val param: Field, val binds: String?, val navigatesTo: List<String>)

enum class ListKind { FLAT, SECTIONED }

data class ListDef(
    val name: String,
    val kind: ListKind,
    val sections: List<String>,
    val element: String,
    val rows: List<String>,
    val fake: FakeSettings? = null,
)

/** A screen ([navigation] set) or a component (row or child pane). */
data class CatalogVm(
    val name: String,
    val spec: String,
    val navigation: Navigation?,
    val identity: String?,
    val state: List<Field>,
    val actions: List<Action>,
    val mutations: List<Mutation>,
    val lists: List<ListDef>,
    val children: List<Field>,
    /** Screens this screen may replace itself with, without a tap (`redirects_to`, `@RedirectsTo`). */
    val redirectsTo: List<String> = emptyList(),
    /**
     * A component every screen hosts without listing it (`hosted: true`, `@HostedComponent`). It has
     * its own navigator for the edges of everything it embeds.
     */
    val hosted: Boolean = false,
) {
    val isScreen: Boolean get() = navigation != null
    val packageName: String get() = spec.substringBeforeLast('.', "")
    val specName: String get() = spec.substringAfterLast('.')

    /** Every component this one embeds directly: list rows, list elements and children. */
    val embedded: List<String> get() = (lists.flatMap { it.rows + it.element } + children.map { it.type }).distinct()
}

data class CatalogTypeDef(
    val name: String,
    val enumCases: List<String>?,
    val fields: List<Field>?,
    val subtypes: List<String>?,
    val external: String?,
)
