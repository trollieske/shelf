plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.24" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.8.0" apply false
}

subprojects {
    configurations.all {
        resolutionStrategy {
            dependencySubstitution {
                substitute(module("org.bouncycastle:bcprov-jdk15on"))
                    .using(module("org.bouncycastle:bcprov-jdk18on:1.78.1"))
                substitute(module("org.bouncycastle:bcpkix-jdk15on"))
                    .using(module("org.bouncycastle:bcpkix-jdk18on:1.78.1"))
                substitute(module("org.bouncycastle:bcprov-jdk15to18"))
                    .using(module("org.bouncycastle:bcprov-jdk18on:1.78.1"))
            }
        }
    }
}
