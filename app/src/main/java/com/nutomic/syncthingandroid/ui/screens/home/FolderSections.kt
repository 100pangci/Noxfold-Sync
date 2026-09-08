package com.nutomic.syncthingandroid.ui.screens.home

/**
 * One collapsible section of the home lists: a group name ("" for the default
 * "ungrouped" section) plus the items in it, in display order.
 */
data class GroupSection<T>(
    val groupName: String,
    val items: List<T>,
)

/**
 * Splits the models into sections by their `group` value.
 *
 * The default section (empty group) always comes first, the other sections
 * follow sorted by [groupNameComparator]. Inside a section the input order is
 * preserved (the lists arrive sorted by name), matching the grouping
 * behaviour of the Syncthing core web GUI.
 */
fun <T> buildGroupSections(
    models: List<T>,
    groupNameOf: (T) -> String,
    groupNameComparator: Comparator<String>,
): List<GroupSection<T>> {
    val byGroup = LinkedHashMap<String, MutableList<T>>()
    for (model in models) {
        byGroup.getOrPut(groupNameOf(model)) { mutableListOf() }.add(model)
    }
    return byGroup
        .keys
        .sortedWith { a, b ->
            when {
                a.isEmpty() && b.isEmpty() -> 0
                a.isEmpty() -> -1
                b.isEmpty() -> 1
                else -> groupNameComparator.compare(a, b)
            }
        }
        .map { name -> GroupSection(name, byGroup.getValue(name)) }
}

/** Splits the folder models into sections by their `group` value. */
fun buildFolderSections(
    models: List<FolderUiModel>,
    groupNameComparator: Comparator<String>,
): List<GroupSection<FolderUiModel>> =
    buildGroupSections(models, { it.group }, groupNameComparator)

/** Splits the device models into sections by their `group` value. */
fun buildDeviceSections(
    models: List<DeviceUiModel>,
    groupNameComparator: Comparator<String>,
): List<GroupSection<DeviceUiModel>> =
    buildGroupSections(models, { it.group }, groupNameComparator)
