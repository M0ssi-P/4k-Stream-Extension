pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }

rootProject.name = "4kstream-extensions"

// every folder with a plugin.json is a plugin
rootDir.listFiles()!!.filter { File(it, "plugin.json").isFile }.sortedBy { it.name }.forEach { include(it.name) }
