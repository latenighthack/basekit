package com.latenighthack.basekit.fake

/**
 * The settings every fake reads when it is created.
 *
 * @param seed the same seed gives the same data on every platform.
 * @param dataSet see [FakeDataSet].
 * @param names picks a text kind or number range from a property name when no annotation sets one.
 * @param listMin and [listMax] bound list sizes no annotation sets: one row by default, since many lists
 *   in a real app hold one row or none (a section or banner that is shown or hidden). The fakes plugin's
 *   `basekitFakes { listMin; listMax }` sets the bounds in the generated code instead.
 * @param nullFrequency how often an unannotated nullable value is null.
 * @param maxDepth below this many nested values, lists are empty and nullable values null, so recursive
 *   types end.
 * @param imageUrl the URL for a [com.latenighthack.basekit.fake.annotations.FakeTextKind.IMAGE_URL]
 *   value, from a seed string and a size.
 */
public data class FakeConfig(
    val seed: Long = 0,
    val dataSet: FakeDataSet = FakeDataSet.TYPICAL,
    val names: FakeNames = FakeNames.Default,
    val listMin: Int = 1,
    val listMax: Int = 1,
    val nullFrequency: Double = 0.2,
    val maxDepth: Int = 8,
    val imageUrl: (seed: String, width: Int, height: Int) -> String = { seed, width, height ->
        "https://picsum.photos/seed/$seed/$width/$height"
    },
)
