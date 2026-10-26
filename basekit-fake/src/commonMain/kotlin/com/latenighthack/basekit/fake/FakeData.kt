package com.latenighthack.basekit.fake

import com.latenighthack.basekit.fake.annotations.FakeTextKind
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Fake values for one place in a fake view model, named by [path] (`HomeViewModel.items[2].state`).
 *
 * Every value is drawn from a random generator seeded by the config's seed, the path and the field
 * name, so the same seed gives the same values on every platform, and adding a field does not change the
 * values of the others. Generated fakes call these functions; providers can too.
 */
public class FakeData(
    public val config: FakeConfig,
    public val path: String,
    public val depth: Int = 0,
) {
    public val dataSet: FakeDataSet get() = config.dataSet

    public fun child(name: String): FakeData = FakeData(config, "$path.$name", depth + 1)

    public fun child(name: String, index: Int): FakeData = FakeData(config, "$path.$name[$index]", depth + 1)

    /** The random generator for [field] at this path. */
    public fun random(field: String): Random = Random(fnv1a64(config.seed, "$path#$field"))

    public fun text(
        field: String,
        kind: FakeTextKind = FakeTextKind.AUTO,
        minLength: Int = -1,
        maxLength: Int = -1,
        values: List<String> = emptyList(),
    ): String {
        val random = random(field)
        if (values.isNotEmpty()) return values[random.nextInt(values.size)]
        val resolved = if (kind == FakeTextKind.AUTO) config.names.kindFor(field) ?: FakeTextKind.WORDS else kind
        var text = text(resolved, field, random)
        while (minLength >= 0 && text.length < minLength) text += " " + FakeWords.words.pick(random)
        if (maxLength >= 0 && text.length > maxLength) text = text.take(maxLength).trimEnd()
        return text
    }

    public fun int(field: String, min: Int? = null, max: Int? = null): Int =
        long(field, min?.toLong(), max?.toLong()).toInt()

    public fun short(field: String, min: Short? = null, max: Short? = null): Short =
        long(field, min?.toLong() ?: 0, max?.toLong() ?: Short.MAX_VALUE.toLong()).toShort()

    public fun byte(field: String, min: Byte? = null, max: Byte? = null): Byte =
        long(field, min?.toLong() ?: 0, max?.toLong() ?: Byte.MAX_VALUE.toLong()).toByte()

    public fun long(field: String, min: Long? = null, max: Long? = null): Long {
        val range = config.names.rangeFor(field) ?: 0L..100L
        val low = min ?: range.first
        val high = max ?: range.last
        if (high <= low) return low
        return random(field).nextLong(low, high + 1)
    }

    /** A value between [min] and [max], rounded to two decimal places. */
    public fun double(field: String, min: Double? = null, max: Double? = null): Double {
        val range = config.names.rangeFor(field)
        val low = min ?: range?.first?.toDouble() ?: 0.0
        val high = max ?: range?.last?.toDouble() ?: 100.0
        if (high <= low) return low
        return (random(field).nextDouble(low, high) * 100).roundToLong() / 100.0
    }

    public fun float(field: String, min: Float? = null, max: Float? = null): Float =
        double(field, min?.toDouble(), max?.toDouble()).toFloat()

    public fun boolean(field: String): Boolean = random(field).nextBoolean()

    public fun char(field: String): Char = 'a' + random(field).nextInt(26)

    /**
     * Null [frequency] of the time (the data set's null frequency when it sets one and [exact] is false,
     * else the config's when [frequency] is null), and always below the config's `maxDepth`.
     */
    public fun <T> nullable(field: String, frequency: Double? = null, exact: Boolean = false, value: () -> T): T? {
        if (depth >= config.maxDepth) return null
        val chance = when {
            exact && frequency != null -> frequency
            else -> dataSet.chain.firstNotNullOfOrNull { it.nullFrequency } ?: frequency ?: config.nullFrequency
        }
        return if (random("$field?").nextDouble() < chance) null else value()
    }

    /**
     * A list size between [min] and [max] (the config's list bounds when null). Unless [exact], it is
     * scaled by the data set. Zero below the config's `maxDepth`.
     */
    public fun size(field: String, min: Int? = null, max: Int? = null, exact: Boolean = false): Int {
        if (depth >= config.maxDepth) return 0
        val low = min ?: config.listMin
        val high = maxOf(low, max ?: if (min != null) maxOf(min, config.listMax) else config.listMax)
        val size = if (high == low) low else random("$field#size").nextInt(low, high + 1)
        if (exact) return size
        val scale = dataSet.chain.fold(1.0) { product, set -> product * set.listScale }
        return (size * scale).roundToInt()
    }

    /** An index into [weights], chosen in proportion to them. */
    public fun choose(field: String, weights: IntArray): Int {
        val total = weights.sum()
        if (total <= 0) return 0
        var pick = random("$field#case").nextInt(total)
        weights.forEachIndexed { index, weight ->
            if (pick < weight) return index
            pick -= weight
        }
        return weights.lastIndex
    }

    /** An index below [count], all equally likely. */
    public fun choose(field: String, count: Int): Int = if (count <= 1) 0 else random("$field#case").nextInt(count)

    /** [size] elements, each made from its own child data (`field[0]`, `field[1]`, …). */
    public fun <T> list(field: String, size: Int, element: (FakeData) -> T): List<T> =
        List(size) { index -> element(child(field, index)) }

    /** Adds a call of [member] at this path to [BasekitFakes.log]. */
    public fun record(member: String, value: Any? = null) {
        BasekitFakes.log.add(FakeEvent(path, member, value))
    }

    /** Records that [member] would navigate to [target] but the fake was given no navigator for it. */
    public fun unreachable(member: String, target: String) {
        BasekitFakes.log.add(FakeEvent(path, member, "no navigator for $target"))
    }

    override fun toString(): String = "FakeData($path)"

    private fun text(kind: FakeTextKind, field: String, random: Random): String {
        val scale = dataSet.chain.fold(1.0) { product, set -> product * set.textScale }
        fun count(low: Int, high: Int) = maxOf(1, (random.nextInt(low, high + 1) * scale).roundToInt())
        fun words(n: Int) = List(n) { FakeWords.words.pick(random) }
        fun sentence() = words(count(5, 12)).joinToString(" ").replaceFirstChar { it.uppercase() } + "."
        fun hex(digits: Int) = buildString { repeat(digits) { append("0123456789abcdef"[random.nextInt(16)]) } }
        return when (kind) {
            FakeTextKind.AUTO, FakeTextKind.WORDS -> words(count(2, 4)).joinToString(" ")
            FakeTextKind.WORD -> FakeWords.words.pick(random)
            FakeTextKind.TITLE -> words(count(2, 4)).joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            FakeTextKind.SENTENCE -> sentence()
            FakeTextKind.PARAGRAPH -> List(random.nextInt(3, 6)) { sentence() }.joinToString(" ")
            FakeTextKind.PERSON_NAME -> "${FakeWords.firstNames.pick(random)} ${FakeWords.lastNames.pick(random)}"
            FakeTextKind.FIRST_NAME -> FakeWords.firstNames.pick(random)
            FakeTextKind.LAST_NAME -> FakeWords.lastNames.pick(random)
            FakeTextKind.USERNAME -> FakeWords.firstNames.pick(random).lowercase() + random.nextInt(10, 1000)
            FakeTextKind.EMAIL -> "${FakeWords.firstNames.pick(random).lowercase()}.${FakeWords.lastNames.pick(random).lowercase()}@${FakeWords.domains.pick(random)}"
            FakeTextKind.PHONE -> "+1 555-01${random.nextInt(10, 100)}"
            FakeTextKind.URL -> "https://${FakeWords.domains.pick(random)}/${FakeWords.words.pick(random)}/${FakeWords.words.pick(random)}"
            FakeTextKind.IMAGE_URL -> config.imageUrl(hex(8), 400, 300)
            FakeTextKind.ID -> {
                val noun = FakeNames.words(field).dropLast(1).joinToString("-").ifEmpty { "id" }
                "$noun-${hex(10)}"
            }
            FakeTextKind.UUID -> "${hex(8)}-${hex(4)}-4${hex(3)}-${"89ab"[random.nextInt(4)]}${hex(3)}-${hex(12)}"
            FakeTextKind.CITY -> FakeWords.cities.pick(random)
            FakeTextKind.COUNTRY -> FakeWords.countries.pick(random)
            FakeTextKind.ADDRESS -> "${random.nextInt(1, 2000)} ${FakeWords.streets.pick(random)}, ${FakeWords.cities.pick(random)}"
            FakeTextKind.COMPANY -> "${FakeWords.words.pick(random).replaceFirstChar { it.uppercase() }} ${FakeWords.companySuffixes.pick(random)}"
            FakeTextKind.COLOR_HEX -> "#${hex(6)}"
            FakeTextKind.DATE -> "20${random.nextInt(20, 30)}-${pad(random.nextInt(1, 13))}-${pad(random.nextInt(1, 29))}"
            FakeTextKind.TIME -> "${pad(random.nextInt(0, 24))}:${pad(random.nextInt(0, 12) * 5)}"
            FakeTextKind.PRICE -> "$${random.nextInt(1, 500)}.${pad(random.nextInt(0, 100))}"
        }
    }

    private fun pad(value: Int) = value.toString().padStart(2, '0')

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]
}

/** 64-bit FNV-1a over [seed]'s bytes then [text]'s UTF-16 code units: the same on every platform. */
internal fun fnv1a64(seed: Long, text: String): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    val prime = 0x100000001b3L
    for (shift in 0 until 64 step 8) {
        hash = (hash xor ((seed ushr shift) and 0xff)) * prime
    }
    for (char in text) {
        hash = (hash xor (char.code.toLong() and 0xff)) * prime
        hash = (hash xor (char.code.toLong() ushr 8)) * prime
    }
    return hash
}
