package com.deepseek.plugin.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object PluginIcons {
    @JvmField
    val TOOLWINDOW: Icon = IconLoader.getIcon("/icons/toolwindow.svg", PluginIcons::class.java)
    @JvmField
    val ACTION: Icon = IconLoader.getIcon("/icons/action.svg", PluginIcons::class.java)
}
