package dev.huenotes

import dev.huenotes.Checklist.Item
import org.junit.Assert.*
import org.junit.Test

class ChecklistTest {
    private fun i(text: String, done: Boolean = false, level: Int = 0) = Item(done, text, level)
    private val groceries = listOf(i("Bakery"), i("Sourdough", true, 1), i("Croissants", false, 1), i("Dairy"), i("Oat milk", true, 1), i("Butter", false, 1), i("Coffee beans", true))

    // ---- text format ---------------------------------------------------------------------------

    @Test fun flatListsParseExactlyAsBefore() {
        assertEquals(listOf(i("a", true), i("b"), i("plain")), Checklist.parse("[x] a\n[ ] b\nplain"))
        assertEquals("[x] a\n[ ] b", Checklist.serialize(listOf(i("a", true), i("b"))))
    }

    @Test fun subItemsUseATwoSpaceIndent() {
        val text = "[ ] Bakery\n  [x] Sourdough\n  [ ] Croissants\n[ ] Dairy"
        val items = Checklist.parse(text)
        assertEquals(listOf(0, 1, 1, 0), items.map { it.level }); assertEquals(text, Checklist.serialize(items))
    }

    @Test fun roundTripKeepsEverythingForALargeList() {
        val items = Checklist.reconcile(groceries)
        assertEquals(items, Checklist.parse(Checklist.serialize(items)))
    }

    @Test fun anyIndentCountsAsOneLevelAndTheFirstRowIsNeverASubItem() {
        assertEquals(listOf(0, 1, 1), Checklist.parse("    [ ] a\n      [ ] b\n\t[ ] c").map { it.level })
        assertEquals(listOf(0, 0), Checklist.parse("  [ ] a\n[ ] b").map { it.level })
    }

    @Test fun parentsFollowTheirSubItemsWhenReadingAndWriting() {
        val items = Checklist.parse("[ ] Parent\n  [x] a\n  [x] b")
        assertTrue("all sub-items done means the parent is done", items[0].done)
        assertEquals("[x] Parent\n  [x] a\n  [x] b", Checklist.serialize(items))
        assertFalse(Checklist.parse("[x] Parent\n  [x] a\n  [ ] b")[0].done)
    }

    @Test fun blankRowsAreDroppedWhenSaving() {
        assertEquals("[ ] a\n  [ ] b", Checklist.serialize(listOf(i("a"), i("  ", false, 1), i("b", false, 1), i("", true))))
        assertEquals("", Checklist.serialize(listOf(i(""), i(" "))))
    }

    @Test fun switchingToTextKeepsTheStructureAsIndentation() {
        val items = Checklist.parse("[ ] Bakery\n  [ ] Sourdough")
        assertEquals("Bakery\n  Sourdough", Checklist.toPlain(items))
        assertEquals(listOf(0, 1), Checklist.parse(Checklist.toPlain(items)).map { it.level })
    }

    // ---- ticking -------------------------------------------------------------------------------

    @Test fun tickingAParentTicksAllItsSubItems() {
        val r = Checklist.tick(groceries, 0, true)
        assertEquals(listOf(true, true, true), r.subList(0, 3).map { it.done }); assertFalse("the other group is untouched", r[3].done)
    }

    @Test fun untickingAParentUnticksAllItsSubItems() {
        val r = Checklist.tick(Checklist.reconcile(groceries), 4 - 1, false)   // Dairy: Oat milk was done
        assertEquals(listOf(false, false, false), r.subList(3, 6).map { it.done })
    }

    @Test fun tickingTheLastOpenSubItemTicksTheParent() {
        val r = Checklist.tick(groceries, 2, true)
        assertTrue(r[0].done); assertTrue(r[1].done); assertTrue(r[2].done)
    }

    @Test fun untickingAnySubItemUnticksTheParent() {
        val allDone = Checklist.tick(groceries, 0, true)
        val r = Checklist.tick(allDone, 1, false)
        assertFalse(r[0].done); assertFalse(r[1].done); assertTrue(r[2].done)
    }

    @Test fun halfMeansSomeButNotAllSubItemsAreDone() {
        assertTrue(Checklist.half(groceries, 0)); assertTrue(Checklist.half(groceries, 3))
        assertFalse("a parent with nothing done", Checklist.half(Checklist.tick(groceries, 0, false), 0))
        assertFalse("a parent with everything done", Checklist.half(Checklist.tick(groceries, 0, true), 0))
        assertFalse("no sub-items", Checklist.half(groceries, 6))
    }

    @Test fun blankSubItemsNeverBlockAParent() {
        val items = listOf(i("Parent"), i("a", true, 1), i("", false, 1))
        assertTrue(Checklist.reconcile(items)[0].done)
        assertEquals("a blank row isn't ticked along", false, Checklist.tick(items, 0, true)[2].done)
    }

    @Test fun aParentWithoutSubItemsTicksOnItsOwn() {
        assertTrue(Checklist.tick(groceries, 6, true)[6].done); assertFalse(Checklist.tick(groceries, 6, false)[6].done)
    }

    // ---- indenting -----------------------------------------------------------------------------

    @Test fun indentTucksARowUnderTheOneAbove() {
        val r = Checklist.indent(listOf(i("Bakery"), i("Croissants")), 1)
        assertEquals(listOf(0, 1), r.map { it.level })
    }

    @Test fun theFirstRowCannotBeIndentedAndSubItemsAreLeftAlone() {
        val flat = listOf(i("a"), i("b"))
        assertSame(flat, Checklist.indent(flat, 0))
        assertSame(groceries, Checklist.indent(groceries, 1))
    }

    @Test fun indentingAParentFlattensItsOwnSubItemsUnderTheNewParent() {
        val r = Checklist.indent(listOf(i("A"), i("B"), i("b1", false, 1)), 1)
        assertEquals(listOf(0, 1, 1), r.map { it.level }); assertEquals(listOf("A", "B", "b1"), r.map { it.text })
    }

    @Test fun indentingChangesTheParentsState() {
        val r = Checklist.indent(listOf(i("Parent", true), i("child", false)), 1)
        assertFalse("the parent now has an open sub-item", r[0].done)
    }

    @Test fun outdentBringsARowBackAndLaterSubItemsFollowIt() {
        val r = Checklist.outdent(listOf(i("A"), i("a1", false, 1), i("a2", false, 1), i("a3", false, 1)), 2)
        assertEquals(listOf(0, 1, 0, 1), r.map { it.level })
        assertSame(groceries, Checklist.outdent(groceries, 0))
    }

    // ---- removing and adding -------------------------------------------------------------------

    @Test fun removingASubItemKeepsTheRest() {
        assertEquals(listOf("Bakery", "Croissants", "Dairy", "Oat milk", "Butter", "Coffee beans"), Checklist.remove(groceries, 1).map { it.text })
    }

    @Test fun removingAParentKeepsItsSubItemsAsTopLevelRows() {
        val r = Checklist.remove(groceries, 0)
        assertEquals(listOf("Sourdough", "Croissants", "Dairy", "Oat milk", "Butter", "Coffee beans"), r.map { it.text })
        assertEquals(listOf(0, 0, 0, 1, 1, 0), r.map { it.level })
    }

    @Test fun theLastRowCannotBeRemoved() {
        val one = listOf(i("only")); assertSame(one, Checklist.remove(one, 0))
    }

    @Test fun enterOnASubItemAddsASiblingSubItem() {
        val (r, at) = Checklist.insertBelow(groceries, 1)
        assertEquals(2, at); assertEquals(1, r[at].level); assertEquals("", r[at].text); assertEquals(8, r.size)
    }

    @Test fun enterOnAParentAddsATopLevelRowAfterItsWholeGroup() {
        val (r, at) = Checklist.insertBelow(groceries, 0)
        assertEquals(3, at); assertEquals(0, r[at].level); assertEquals("Dairy", r[at + 1].text)
    }

    @Test fun enterOnAPlainRowAddsAPlainRow() {
        val (r, at) = Checklist.insertBelow(listOf(i("a"), i("b")), 0)
        assertEquals(1, at); assertEquals(0, r[at].level)
    }

    // ---- counting ------------------------------------------------------------------------------

    @Test fun countsIncludeSubItemsAndSkipBlankRows() {
        assertEquals(7 to 3, Checklist.counts(groceries))
        assertEquals(0 to 0, Checklist.counts(listOf(i(""), i("  "))))
        assertEquals(2 to 1, Checklist.counts(listOf(i("a", true), i("b"), i(""))))
    }

    @Test fun grouping() {
        assertEquals(3, Checklist.groupEnd(groceries, 0)); assertEquals(2, Checklist.groupEnd(groceries, 1)); assertEquals(7, Checklist.groupEnd(groceries, 6))
        assertTrue(Checklist.isParent(groceries, 0)); assertFalse(Checklist.isParent(groceries, 1)); assertFalse(Checklist.isParent(groceries, 6))
    }
}
