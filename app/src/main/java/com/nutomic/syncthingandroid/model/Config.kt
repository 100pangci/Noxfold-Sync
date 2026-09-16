package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Config(
    var version: Int = 0,
    var devices: MutableList<Device>? = null,
    var folders: MutableList<Folder>? = null,
    var gui: Gui? = null,
    var options: Options? = null,
    var defaults: Defaults? = null,
    var remoteIgnoredDevices: MutableList<RemoteIgnoredDevice>? = null,
)
