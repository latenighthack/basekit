package com.latenighthack.basekit.catalog.codegen

/** One `catalog/v1` document: every screen, every component, and the types they refer to. */
data class Catalog(
    val screens: List<CatalogViewModel>,
    val componentGroups: List<CatalogComponentGroup>,
    val types: List<CatalogType>,
)

/** Components written under one comment header, e.g. the rows and panes only `Home` embeds. */
data class CatalogComponentGroup(val header: String, val components: List<CatalogViewModel>)

/**
 * A screen (a `@Destination`, [navigation] set) or a component (a row or child pane, [navigation]
 * null). [spec] is the qualified name of the interface the entry was read from.
 */
data class CatalogViewModel(
    val name: String,
    val spec: String,
    val navigation: CatalogNavigation? = null,
    val identity: String? = null,
    val state: List<CatalogField> = emptyList(),
    val actions: List<CatalogAction> = emptyList(),
    val mutations: List<CatalogMutation> = emptyList(),
    val lists: List<CatalogList> = emptyList(),
    val children: List<CatalogField> = emptyList(),
    /** `@RedirectsTo` targets: screens this screen replaces itself with without a user action. */
    val redirectsTo: List<String> = emptyList(),
    /** A `@HostedComponent`: screens host it without listing it, and it has its own navigator. */
    val hosted: Boolean = false,
)

/** A `name: Type` pair. For `children`, the type is a component name. */
data class CatalogField(val name: String, val type: String)

data class CatalogNavigation(
    val args: List<CatalogField>,
    val route: String?,
    val routeArgs: List<String>,
    val response: String?,
    /** `@Route(aliases)`: other paths that open the screen. */
    val routeAliases: List<String> = emptyList(),
)

/** A zero-argument operation, and the screens its `@NavigateTo` annotations open. */
data class CatalogAction(val name: String, val navigatesTo: List<String> = emptyList())

/**
 * A one-argument operation. [binds] is the State property it writes, when one matches. catalog/v1
 * does not allow [navigatesTo] on a mutation; it is written anyway when the code declares it.
 */
data class CatalogMutation(
    val name: String,
    val param: CatalogField,
    val binds: String? = null,
    val navigatesTo: List<String> = emptyList(),
)

enum class CatalogListKind(val yamlName: String) { FLAT("flat"), SECTIONED("sectioned") }

data class CatalogList(
    val name: String,
    val kind: CatalogListKind,
    val sections: List<String>,
    val element: String,
    val rows: List<String>,
)

/**
 * A type referenced from state, a param, args or a response. Exactly the fields that apply are set:
 * [enumCases] for an enum; [fields] for a class; [subtypes] for a sealed type; [external] (its
 * qualified name) for a type declared outside this compilation, whose fields are not read.
 */
data class CatalogType(
    val name: String,
    val enumCases: List<String>? = null,
    val fields: List<CatalogField>? = null,
    val subtypes: List<String>? = null,
    val external: String? = null,
)
