package com.latenighthack.basekit.catalog.tool

/** A `catalog/v1` document as read from YAML. See docs/guides/catalog.md for the format. */
data class CatalogDocument(
    val screens: List<CatalogVm>,
    val components: List<CatalogVm>,
    val types: List<CatalogTypeDef>,
)

/** A `name: Type` pair; for `children` the type is a component name. */
data class Field(val name: String, val type: String)

data class Navigation(
    val args: List<Field>,
    val route: String?,
    val routeArgs: List<String>,
    val response: String?,
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
