package com.latenighthack.basekit.fake

/**
 * A named data set: how much fake data the fakes make. Annotations with no `dataSet` are scaled by it:
 * list sizes by [listScale], word counts by [textScale]; when [nullFrequency] is set it replaces every
 * unnamed null frequency. Annotations naming this data set, or one it is [based on][base], are used as
 * written.
 */
public class FakeDataSet(
    public val name: String,
    public val base: FakeDataSet? = null,
    public val listScale: Double = 1.0,
    public val textScale: Double = 1.0,
    public val nullFrequency: Double? = null,
) {
    /** This data set, then its base, then the base's base. */
    public val chain: List<FakeDataSet> get() = generateSequence(this) { it.base }.toList()

    /** Whether this data set is [name] or is based on it. */
    public fun isOrExtends(name: String): Boolean = chain.any { it.name == name }

    /** The first of [names] found along [chain], or null. Generated code selects per-data-set values with it. */
    public fun firstOf(vararg names: String): String? = chain.firstOrNull { it.name in names }?.name

    override fun toString(): String = name

    public companion object {
        /** The default: one row per list unless annotated, and some null values. */
        public val TYPICAL: FakeDataSet = FakeDataSet("typical")

        /** Every list empty and every nullable value null. */
        public val EMPTY: FakeDataSet = FakeDataSet("empty", base = TYPICAL, listScale = 0.0, nullFrequency = 1.0)

        /** Ten times the rows, twice the words, no null values. */
        public val LARGE: FakeDataSet = FakeDataSet("large", base = TYPICAL, listScale = 10.0, textScale = 2.0, nullFrequency = 0.0)
    }
}
