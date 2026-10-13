package com.latenighthack.basekit.catalog.tool

/**
 * Decides which file declares each catalog type. A type that only one spec file uses is declared in
 * that file, after the specs; every other type goes in `CatalogTypes.kt`. A use is a State field,
 * mutation param, navigation arg or response, or a field of another type, which passes on its own
 * uses. A dotted name such as `Button.Style` is placed with `Button`, and a sealed type with its class
 * subtypes, which Kotlin requires in one package.
 */
class TypePlacement(private val analysis: CatalogAnalysis) {

    private val declared = analysis.document.types.filter { it.external == null }.associateBy { it.name }

    /** Top-level type name -> the root or shared component whose spec file declares it. */
    private val owners: Map<String, CatalogVm>

    init {
        // Group top-level names that must share a file: a sealed type and its class subtypes.
        val group = declared.keys.map { top(it) }.associateWith { it }.toMutableMap()
        fun find(name: String): String = group.getValue(name).let { if (it == name) it else find(it) }
        declared.values.forEach { type ->
            type.subtypes.orEmpty().filter { it in declared }.forEach { group[find(top(it))] = find(top(type.name)) }
        }

        val users = mutableMapOf<String, MutableSet<String>>()
        fun use(type: String, file: String): Boolean = references(type).fold(false) { changed, name ->
            users.getOrPut(find(top(name))) { mutableSetOf() }.add(file) || changed
        }
        (analysis.screens + analysis.components).forEach { vm ->
            val file = specFile(vm).name
            val navigation = vm.navigation
            (vm.state + vm.mutations.map { it.param } + navigation?.args.orEmpty()).forEach { use(it.type, file) }
            navigation?.response?.let { use(it, file) }
        }
        do {
            var changed = false
            declared.values.forEach { type ->
                val from = users[find(top(type.name))].orEmpty().toList()
                type.fields.orEmpty().forEach { field -> from.forEach { changed = use(field.type, it) || changed } }
            }
        } while (changed)

        owners = declared.keys.map { top(it) }.distinct().mapNotNull { name ->
            users[find(name)]?.singleOrNull()?.let { name to analysis.vm(it) }
        }.toMap()
    }

    /** The vm whose spec file declares [typeName], or null for `CatalogTypes.kt`. */
    fun owner(typeName: String): CatalogVm? = owners[top(typeName)]

    /** The spec file [vm] is written in, named by its first declaration: a root, or a shared component. */
    private fun specFile(vm: CatalogVm): CatalogVm = if (vm in analysis.roots) vm else analysis.owner(vm) ?: vm

    /** Declared catalog types named in [type], including type arguments. */
    private fun references(type: String): List<String> {
        fun names(ref: TypeRef): List<String> = listOf(ref.name) + ref.arguments.flatMap { names(it) }
        return names(TypeRef.parse(type)).filter { it in declared }
    }

    private fun top(name: String) = name.substringBefore('.')
}
