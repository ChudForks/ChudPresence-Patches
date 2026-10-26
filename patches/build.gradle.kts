group = "dev.chudpresence"

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}

patches {
    about {
        name = "ChudPresence ReVanced patches"
        description = "Hulu, YouTube and YouTube Music patches for richer media metadata and presence links"
        source = "https://github.com/ChudForks/ChudPresence-Patches"
        author = "ChudPresence"
        contact = "https://github.com/ChudForks/ChudPresence-Patches/issues"
        website = "https://github.com/ChudForks/ChudPresence-Patches"
        license = "GNU General Public License v3.0"
    }
}
