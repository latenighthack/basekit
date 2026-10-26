package com.latenighthack.basekit.fake

import com.latenighthack.basekit.fake.annotations.FakeTextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeDataTest {
    private fun data(config: FakeConfig = FakeConfig()) = FakeData(config, "Spec")

    @Test
    fun fnvMatchesTheReferenceValues() {
        // Fixed values: the same on every platform, so a seed gives the same data everywhere.
        assertEquals(6673208089802746195L, fnv1a64(0, "Spec#title"))
        assertEquals(-3301645352520322691L, fnv1a64(42, "Home.items[0]#title"))
        assertNotEquals(fnv1a64(0, "a"), fnv1a64(1, "a"))
        assertNotEquals(fnv1a64(0, "a"), fnv1a64(0, "b"))
    }

    @Test
    fun theSameSeedGivesTheSameValues() {
        val first = data(FakeConfig(seed = 7))
        val second = data(FakeConfig(seed = 7))
        assertEquals(first.text("title"), second.text("title"))
        assertEquals(first.int("count"), second.int("count"))
        assertNotEquals(
            List(5) { data(FakeConfig(seed = 7)).child("row", it).text("title") },
            List(5) { data(FakeConfig(seed = 8)).child("row", it).text("title") },
        )
    }

    @Test
    fun aValueDoesNotDependOnOtherFields() {
        val alone = data().text("title")
        val fake = data()
        fake.text("subtitle")
        fake.int("count")
        assertEquals(alone, fake.text("title"))
    }

    @Test
    fun namesPickTextKinds() {
        val fake = data()
        assertTrue('@' in fake.text("email"))
        assertTrue(fake.text("avatarUrl").startsWith("https://"))
        assertTrue(fake.text("userId").startsWith("user-"))
        assertEquals(FakeTextKind.PERSON_NAME, FakeNames.Default.kindFor("authorName"))
        assertEquals(FakeTextKind.TITLE, FakeNames.Default.kindFor("productName"))
        assertEquals(FakeTextKind.USERNAME, FakeNames.Default.kindFor("userName"))
        assertEquals(listOf("avatar", "image", "url"), FakeNames.words("avatarImageURL"))
        val rating = fake.int("rating")
        assertTrue(rating in 1..5)
    }

    @Test
    fun textLengthLimitsHold() {
        val fake = data()
        assertTrue(fake.text("body", FakeTextKind.PARAGRAPH, maxLength = 20).length <= 20)
        assertTrue(fake.text("word", FakeTextKind.WORD, minLength = 40).length >= 40)
        assertEquals("b", fake.text("x", values = listOf("b")))
    }

    @Test
    fun dataSetsScaleListsAndNulls() {
        val typical = data()
        val empty = data(FakeConfig(dataSet = FakeDataSet.EMPTY))
        val large = data(FakeConfig(dataSet = FakeDataSet.LARGE))
        assertTrue(typical.size("items", 3, 8) in 3..8)
        assertEquals(0, empty.size("items", 3, 8))
        assertTrue(large.size("items", 3, 8) in 30..80)
        assertEquals(5, empty.size("items", 5, 5, exact = true))
        assertNull(empty.nullable("subtitle", 0.0) { "x" })
        assertEquals("x", large.nullable("subtitle", 1.0) { "x" })
        assertEquals("x", empty.nullable("subtitle", 0.0, exact = true) { "x" })
    }

    @Test
    fun anUnsizedListHasOneRowUnlessTheDataSetScalesIt() {
        assertEquals(1, data().size("items"))
        assertEquals(0, data(FakeConfig(dataSet = FakeDataSet.EMPTY)).size("items"))
        assertEquals(10, data(FakeConfig(dataSet = FakeDataSet.LARGE)).size("items"))
        assertEquals(4, data(FakeConfig(listMin = 4, listMax = 4)).size("items"))
    }

    @Test
    fun dataSetsChain() {
        val huge = FakeDataSet("huge", base = FakeDataSet.LARGE, listScale = 2.0)
        assertTrue(huge.isOrExtends("large"))
        assertTrue(huge.isOrExtends("typical"))
        assertEquals("large", huge.firstOf("typical", "large"))
        assertEquals("huge", huge.firstOf("large", "huge"))
        assertNull(FakeDataSet.TYPICAL.firstOf("large"))
        assertTrue(FakeData(FakeConfig(dataSet = huge), "x").size("items", 3, 3) == 60)
    }

    @Test
    fun depthEndsRecursion() {
        var fake = data(FakeConfig(maxDepth = 2))
        repeat(2) { fake = fake.child("next") }
        assertEquals(0, fake.size("items"))
        assertNull(fake.nullable("next", 0.0, exact = true) { "x" })
    }

    @Test
    fun chooseFollowsWeights() {
        val fake = data()
        assertEquals(1, fake.choose("kind", intArrayOf(0, 1, 0)))
        assertTrue(fake.choose("kind", 3) in 0..2)
    }

    @Test
    fun recordAddsToTheLog() {
        BasekitFakes.log.clear()
        data().record("onRefresh")
        assertEquals(listOf(FakeEvent("Spec", "onRefresh")), BasekitFakes.log.events.value)
    }
}
