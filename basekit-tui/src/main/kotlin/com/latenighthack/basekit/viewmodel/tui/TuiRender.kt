package com.latenighthack.basekit.viewmodel.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element

/**
 * Small builders over the TamboUI DSL that the generated screens call, so codegen stays compact and
 * the rendering conventions (state -> two-column table, list -> selectable list, actions -> hint bar)
 * live in one place.
 */
public object TuiRender {

    /** Renders a `State` data class as a bordered two-column `Field | Value` table. */
    public fun stateTable(title: String, rows: List<Pair<String, String>>): Element {
        val keyWidth = (rows.maxOfOrNull { it.first.length } ?: 5).coerceAtLeast(5)
        // TamboUI tables render empty without explicit column widths, so a fixed key column + a
        // filling value column is required for anything to appear.
        val table = Toolkit.table()
            .header("Field", "Value")
            .widths(Toolkit.length(keyWidth + 1), Toolkit.fill())
            .columnSpacing(2)
        for ((name, value) in rows) {
            table.row(name, value)
        }
        return table.title(title).rounded()
    }

    /** Renders a `@ViewModelList` as a bordered, selectable list. */
    public fun selectableList(title: String, rows: List<String>, selected: Int): Element {
        val list = Toolkit.list(rows)
        if (rows.isNotEmpty()) {
            list.selected(selected.coerceIn(0, rows.size - 1))
        }
        return list.title(title).rounded()
    }

    /** Renders the footer of key-bound actions, e.g. `[i] onIncrement  [r] onReset`. */
    public fun actionsBar(hints: List<String>): Element =
        Toolkit.text(if (hints.isEmpty()) "" else hints.joinToString("  ")).dim()

    /**
     * Renders the overlay for an in-progress mutation prompt: a `true`/`false` chooser for boolean
     * arguments, or the current text buffer for text entry, above a dim hint line of the accepted keys.
     */
    public fun prompt(label: String, isBool: Boolean, text: String): Element {
        val body = if (isBool) "[t] true    [f] false" else "> $text"
        val hint = if (isBool) "[t/f] choose   [Esc] cancel" else "[Enter] submit   [Esc] cancel"
        return Toolkit.panel(
            label,
            Toolkit.column(Toolkit.text(body), Toolkit.text(hint).dim()),
        ).rounded()
    }
}
