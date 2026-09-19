package com.nutomic.syncthingandroid.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ListDetailBackStackTest {

    @Test
    fun siblingSelection_replacesPreviousDetailBranch() {
        val stack = mutableListOf("home", "folder-a", "picker")

        stack.replaceAfterLast(isAnchor = { it == "home" }, route = "folder-b")

        assertEquals(listOf("home", "folder-b"), stack)
    }

    @Test
    fun selectingCurrentDetail_isNoOp() {
        val stack = mutableListOf("home", "folder-a")

        stack.replaceAfterLast(isAnchor = { it == "home" }, route = "folder-a")

        assertEquals(listOf("home", "folder-a"), stack)
    }

    @Test
    fun selectingUnderlyingDetail_closesOnlyNestedRoutes() {
        val stack = mutableListOf("home", "folder-a", "picker")

        stack.replaceAfterLast(isAnchor = { it == "home" }, route = "folder-a")

        assertEquals(listOf("home", "folder-a"), stack)
    }

    @Test
    fun clearingDetail_keepsRootOnly() {
        val stack = mutableListOf("home", "folder-a", "conditions")

        stack.clearAfterLast { it == "home" }

        assertEquals(listOf("home"), stack)
    }
}
