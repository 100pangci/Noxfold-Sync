package com.nutomic.syncthingandroid.ui.nav

/**
 * Holds per-route draft/edit state OUTSIDE the Navigation 3 entry composition.
 *
 * Why this exists: Navigation 3 (1.1.3) only composes the top entry and
 * disposes entries covered by another route, so any `remember {}` state inside
 * an entry is destroyed when the user navigates deeper (e.g. from a folder or
 * device editor to its custom sync conditions screen) - unsaved edits are lost
 * on return. A plain `rememberSaveable` is not a full fix either: NavDisplay
 * never clears its SaveableStateHolder on pop (verified against the 1.1.3
 * bytecode), so drafts saved by it resurrect when the same route is re-opened
 * after the edit session actually ended.
 *
 * Lifecycle: a state object lives exactly as long as its route is on the back
 * stack. The host activity (MainActivity) watches the stack and calls
 * [retainAll] whenever the set of live keys changes, so finishing an edit
 * session (save, discard, delete) always evicts the draft and the next open
 * starts fresh.
 *
 * The factory is called lazily per key; keys are derived from the route
 * arguments by each screen (see folderEditStateKey / deviceEditStateKey).
 */
class EditStateStore<T>(private val factory: () -> T) {

    private val states = mutableMapOf<String, T>()

    fun stateFor(key: String): T = states.getOrPut(key, factory)

    /** Returns an existing route state without creating a new draft. */
    fun stateOrNull(key: String): T? = states[key]

    /** Evicts every state object whose route is no longer on the back stack. */
    fun retainAll(keys: Set<String>) {
        states.keys.retainAll(keys)
    }
}
