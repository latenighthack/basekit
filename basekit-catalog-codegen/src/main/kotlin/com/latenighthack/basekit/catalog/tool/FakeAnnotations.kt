package com.latenighthack.basekit.catalog.tool

/**
 * The fake annotations a catalog's `fake:` keys stand for (see docs/guides/catalog-codegen.md). They
 * change nothing in a real build; basekit's generated fakes read them.
 */
object FakeAnnotations {

    /** `@FakeList`, `@FakeRows` and `@FakePlaceholder` for a list; [reference] names a row's spec. */
    fun list(fake: FakeSettings, reference: (String) -> String, imports: MutableSet<String>): List<String> = buildList {
        fun add(values: FakeValues, dataSet: String?) {
            val sizes = when {
                values.size != null -> listOf("size = ${values.size}")
                else -> listOfNotNull(values.min?.let { "min = $it" }, values.max?.let { "max = $it" })
            }
            if (sizes.isNotEmpty()) {
                imports += Symbols.FAKE_LIST
                add("@FakeList(${(sizes + dataSet(dataSet)).joinToString(", ")})")
            }
            if (values.rows.isNotEmpty()) {
                imports += Symbols.FAKE_ROWS
                add("@FakeRows(${(listOf(values.rows.joinToString(", ", "[", "]") { "${reference(it)}::class" }) + dataSet(dataSet)).joinToString(", ")})")
            }
        }
        add(fake.base, null)
        fake.dataSets.forEach { (name, values) -> add(values, name) }
        fake.placeholder?.let {
            imports += Symbols.FAKE_PLACEHOLDER
            add("@FakePlaceholder(${reference(it)}::class)")
        }
    }

    /** `@FakeText`, `@FakeNumber`, `@FakeNull`, `@FakeCase` and `@FakeValue` for a State field. */
    fun field(fake: FakeSettings, imports: MutableSet<String>): List<String> = buildList {
        fun add(values: FakeValues, dataSet: String?) {
            values.text?.let { text ->
                imports += Symbols.FAKE_TEXT
                val arguments = buildList {
                    text.kind?.let {
                        imports += Symbols.FAKE_TEXT_KIND
                        add("FakeTextKind.$it")
                    }
                    text.minLength?.let { add("minLength = $it") }
                    text.maxLength?.let { add("maxLength = $it") }
                    if (text.values.isNotEmpty()) add("values = ${text.values.joinToString(", ", "[", "]") { kotlinString(it) }}")
                } + dataSet(dataSet)
                add("@FakeText(${arguments.joinToString(", ")})")
            }
            values.number?.let { (min, max) ->
                imports += Symbols.FAKE_NUMBER
                add("@FakeNumber(${(listOf("min = $min", "max = $max") + dataSet(dataSet)).joinToString(", ")})")
            }
            values.nullFrequency?.let {
                imports += Symbols.FAKE_NULL
                add("@FakeNull(${(listOf("$it") + dataSet(dataSet)).joinToString(", ")})")
            }
            if (values.cases.isNotEmpty()) {
                imports += Symbols.FAKE_CASE
                add("@FakeCase(${(listOf(values.cases.joinToString(", ", "[", "]") { kotlinString(it) }) + dataSet(dataSet)).joinToString(", ")})")
            }
            values.value?.let {
                imports += Symbols.FAKE_VALUE
                add("@FakeValue(${(listOf(kotlinString(it)) + dataSet(dataSet)).joinToString(", ")})")
            }
        }
        add(fake.base, null)
        fake.dataSets.forEach { (name, values) -> add(values, name) }
    }

    private fun dataSet(name: String?): List<String> = listOfNotNull(name?.let { "dataSet = ${kotlinString(it)}" })
}
