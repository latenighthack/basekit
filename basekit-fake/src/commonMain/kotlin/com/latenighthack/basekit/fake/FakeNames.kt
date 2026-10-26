package com.latenighthack.basekit.fake

import com.latenighthack.basekit.fake.annotations.FakeTextKind

/**
 * A rule matching a property name, split into lowercase words (`avatarImageUrl` -> `avatar`, `image`,
 * `url`), to the text kind or number range it gets when no annotation sets one.
 */
public class FakeNameRule(
    public val kind: FakeTextKind? = null,
    public val range: LongRange? = null,
    public val matches: (words: List<String>) -> Boolean,
)

/**
 * The text kinds and number ranges picked from property names. The first matching rule wins; add rules
 * in front of the defaults with [plus]:
 *
 * ```
 * FakeNames.Default + FakeNameRule(FakeTextKind.COMPANY) { it.last() == "brand" }
 * ```
 */
public class FakeNames(public val rules: List<FakeNameRule>) {

    public operator fun plus(rule: FakeNameRule): FakeNames = FakeNames(listOf(rule) + rules)

    public fun kindFor(name: String): FakeTextKind? {
        val words = words(name)
        return rules.firstOrNull { it.kind != null && it.matches(words) }?.kind
    }

    public fun rangeFor(name: String): LongRange? {
        val words = words(name)
        return rules.firstOrNull { it.range != null && it.matches(words) }?.range
    }

    public companion object {
        /** `avatarImageURL` -> [avatar, image, url]; `user_name` -> [user, name]. */
        public fun words(name: String): List<String> = name
            .split('_', '-', ' ')
            .flatMap { part -> part.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

        private fun last(vararg names: String): (List<String>) -> Boolean = { it.lastOrNull() in names }
        private fun any(vararg names: String): (List<String>) -> Boolean = { words -> words.any { it in names } }

        private val PERSON_WORDS = setOf("author", "owner", "sender", "recipient", "person", "contact", "user", "member", "full", "display", "friend", "host", "guest")

        public val Default: FakeNames = FakeNames(
            listOf(
                FakeNameRule(FakeTextKind.EMAIL, matches = any("email", "mail")),
                FakeNameRule(FakeTextKind.IMAGE_URL, matches = { words ->
                    words.any { it in setOf("image", "avatar", "photo", "thumbnail", "picture", "icon", "cover") } &&
                        (words.last() in setOf("url", "uri", "href", "src") || words.size == 1 || words.last() in setOf("image", "avatar", "photo", "thumbnail", "picture", "icon", "cover"))
                }),
                FakeNameRule(FakeTextKind.URL, matches = last("url", "uri", "link", "href", "website", "homepage")),
                FakeNameRule(FakeTextKind.PHONE, matches = any("phone", "mobile", "tel")),
                FakeNameRule(FakeTextKind.UUID, matches = last("uuid", "guid")),
                FakeNameRule(FakeTextKind.ID, matches = last("id", "ids", "key", "identifier")),
                FakeNameRule(FakeTextKind.FIRST_NAME, matches = { it.takeLast(2) == listOf("first", "name") || it.last() == "firstname" || it.last() == "forename" }),
                FakeNameRule(FakeTextKind.LAST_NAME, matches = { it.takeLast(2) == listOf("last", "name") || it.last() in setOf("lastname", "surname") }),
                FakeNameRule(FakeTextKind.USERNAME, matches = { it.takeLast(2) == listOf("user", "name") || it.last() in setOf("username", "handle", "nickname", "login") }),
                FakeNameRule(FakeTextKind.PERSON_NAME, matches = { words ->
                    words.last() == "name" && (words.size == 1 || words.dropLast(1).any { it in PERSON_WORDS })
                }),
                FakeNameRule(FakeTextKind.TITLE, matches = last("name", "title", "heading", "headline", "label", "caption")),
                FakeNameRule(FakeTextKind.PARAGRAPH, matches = last("body", "bio", "content", "about", "details")),
                FakeNameRule(FakeTextKind.SENTENCE, matches = last("subtitle", "summary", "description", "message", "text", "note", "comment", "hint", "excerpt", "status", "reason", "error")),
                FakeNameRule(FakeTextKind.CITY, matches = last("city", "town")),
                FakeNameRule(FakeTextKind.COUNTRY, matches = last("country", "nation")),
                FakeNameRule(FakeTextKind.ADDRESS, matches = last("address", "street", "location")),
                FakeNameRule(FakeTextKind.COMPANY, matches = last("company", "organization", "organisation", "org", "employer", "brand")),
                FakeNameRule(FakeTextKind.COLOR_HEX, matches = last("color", "colour", "tint")),
                FakeNameRule(FakeTextKind.DATE, matches = last("date", "day", "birthday")),
                FakeNameRule(FakeTextKind.TIME, matches = last("time")),
                FakeNameRule(FakeTextKind.PRICE, matches = last("price", "cost", "amount", "total", "fee")),
                FakeNameRule(FakeTextKind.WORD, matches = last("tag", "tags", "category", "categories", "kind", "type", "role", "word", "keyword", "keywords")),

                FakeNameRule(range = 0L..250L, matches = last("count", "total", "number", "quantity", "size", "length", "likes", "views")),
                FakeNameRule(range = 18L..90L, matches = last("age")),
                FakeNameRule(range = 1990L..2030L, matches = last("year")),
                FakeNameRule(range = 1L..5L, matches = last("rating", "stars", "score")),
                FakeNameRule(range = 0L..100L, matches = last("percent", "percentage", "progress")),
                FakeNameRule(range = 1L..500L, matches = last("price", "cost", "amount", "fee")),
                FakeNameRule(range = 0L..20L, matches = last("index", "position", "rank", "order")),
            ),
        )
    }
}
