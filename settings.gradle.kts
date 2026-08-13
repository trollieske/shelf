pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Shelf"

include(":app")
include(":core")
include(":designsystem")
include(":data")
include(":library")
include(":reader")
include(":player")
include(":ftp")
include(":smb")
include(":webdav")
include(":torrent")
include(":pagecurl")
