package com.latenighthack.basekit.fake.annotations

import kotlin.reflect.KClass

// Annotations read by basekit-fake-ksp when it writes the fake implementation of each @ViewModelSpec.
// None of them changes a real build.
//
// The shape annotations go on properties: `State` constructor `val`s, `@ViewModelList` properties, Args
// properties and other spec properties. Each takes an optional `dataSet`:
//  - empty: the value for every data set, scaled by the data set where that applies (list sizes and
//    text lengths are scaled; the built-in `empty` data set gives empty lists and null nullable values);
//  - a name: the value for that data set and every data set based on it, used as written.
// So `@FakeList(min = 3, max = 8) @FakeList(size = 300, dataSet = "large")` gives 3-8 rows normally,
// none in `empty` and exactly 300 in `large`.

/** What a generated `String` looks like. [AUTO] picks a kind from the property name, else [WORDS]. */
public enum class FakeTextKind {
    AUTO,
    WORD,
    WORDS,
    TITLE,
    SENTENCE,
    PARAGRAPH,
    PERSON_NAME,
    FIRST_NAME,
    LAST_NAME,
    USERNAME,
    EMAIL,
    PHONE,
    URL,
    IMAGE_URL,
    ID,
    UUID,
    CITY,
    COUNTRY,
    ADDRESS,
    COMPANY,
    COLOR_HEX,
    DATE,
    TIME,
    PRICE,
}

/**
 * A `String` value: its [kind], a length limit in characters ([minLength], [maxLength]; -1 for none),
 * or a fixed set of [values] to choose from.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeText(
    val kind: FakeTextKind = FakeTextKind.AUTO,
    val minLength: Int = -1,
    val maxLength: Int = -1,
    val values: Array<String> = [],
    val dataSet: String = "",
)

/** A number (`Int`, `Long`, `Short`, `Byte`, `Double` or `Float`) between [min] and [max] inclusive. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeNumber(
    val min: Double,
    val max: Double,
    val dataSet: String = "",
)

/**
 * How many entries a `@ViewModelList` (per section, for a sectioned list) or a `List`/`Set`/`Map` field
 * has: exactly [size], or between [min] and [max]. -1 leaves a bound at its default.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeList(
    val size: Int = -1,
    val min: Int = -1,
    val max: Int = -1,
    val dataSet: String = "",
)

/**
 * Which of a `@ViewModelList`'s possible row types appear, and how often: [types] is a subset of the
 * list's `possibleTypes`, and [weights] (one per type, empty for equal weights) sets their frequency.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeRows(
    val types: Array<KClass<*>>,
    val weights: IntArray = [],
    val dataSet: String = "",
)

/**
 * The row type a `@ViewModelList` shows only when it has no other rows: the placeholder its
 * implementation inserts with `ifEmpty`, such as "No credentials". Fakes never choose it among the
 * other row types; a list that comes out empty (always, in the `empty` data set) shows one placeholder.
 * [type] is one of the list's `possibleTypes`.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class FakePlaceholder(val type: KClass<*>)

/** How often a nullable value is null: 0.0 never, 1.0 always. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeNull(
    val frequency: Double,
    val dataSet: String = "",
)

/**
 * Which cases an enum or sealed value takes, by name (enum entry or sealed subclass simple name), with
 * [weights] (one per case, empty for equal weights). On a sectioned `@ViewModelList` it picks the
 * section headers, in the order given.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeCase(
    val cases: Array<String>,
    val weights: IntArray = [],
    val dataSet: String = "",
)

/** Takes the value from the `@FakeProvider` function named [provider]. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeValue(
    val provider: String,
    val dataSet: String = "",
)

/**
 * Defaults for every list and nullable value in one view model spec: list sizes between [listMin] and
 * [listMax], and [nullFrequency]. -1 leaves a default unchanged.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeDefaults(
    val listMin: Int = -1,
    val listMax: Int = -1,
    val nullFrequency: Double = -1.0,
    val dataSet: String = "",
)

/**
 * Which `@RedirectsTo` target the fake of a destination redirects to while its `state` is collected.
 * Without it, the fake redirects to the first target in declaration order. [target] is one of the
 * destination's `@RedirectsTo` targets, or the destination itself to keep the fake on its own screen
 * (for a screen whose real implementation redirects only in some cases).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class FakeRedirect(
    val target: KClass<*>,
    val dataSet: String = "",
)

/**
 * Marks the action that answers a responding destination. In the fake, calling it responds with a
 * generated value of the destination's response type. It may be on the destination's spec or on a row
 * or child spec it embeds.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class FakeResponds

/**
 * A function that makes fake values of its return type, for types the generator cannot build (types
 * from other libraries, say) or to replace generation of a whole `State`. It is a top-level function,
 * either `fun FakeData.x(): T` or `fun x(fake: FakeData): T`. It is used for every value of type `T`,
 * or, when [name] is set, only where `@FakeValue(name)` asks for it. [dataSet] limits it to one data set
 * and those based on it.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class FakeProvider(
    val name: String = "",
    val dataSet: String = "",
)
