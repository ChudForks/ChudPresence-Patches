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
        description = "YouTube Music patches for richer Android media-session metadata"
        source = "https://github.com/chudpresence/mobile"
        author = "ChudPresence"
        contact = "https://github.com/chudpresence/mobile/issues"
        website = "https://github.com/chudpresence/mobile"
        license = "GNU General Public License v3.0"
    }
}
