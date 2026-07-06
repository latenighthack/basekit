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
        val table = Toolkit.table().header("Field", "Value")
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
}
