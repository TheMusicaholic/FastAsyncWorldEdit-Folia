import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension

plugins {
    id("buildlogic.adapter")
}

dependencies {
    // https://artifactory.papermc.io/ui/native/universe/io/papermc/paper/dev-bundle/
    the<PaperweightUserDependenciesExtension>().paperDevBundle("26.1.2.build.+")
    compileOnly(libs.paperLib)
}

// Minecraft 26.1+ requires Java 25 to run (paperweight runs the server jar during setup). 26.1 ships unobfuscated, so
// the remapping step that limits the other adapters to Java 24 does not apply here.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
