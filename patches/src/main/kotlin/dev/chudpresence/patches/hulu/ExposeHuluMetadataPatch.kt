package dev.chudpresence.patches.hulu

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.predicate

private const val PRESENTER = "Lcom/hulu/features/playback/PlayerPresenter;"
private const val START_INFO = "Lcom/hulu/features/playback/model/PlaybackStartInfo;"
private const val MANAGER = "Lcom/hulu/features/playback/mediasession/MediaSessionStateManager;"
private const val ENTITY = "Lcom/hulu/browse/model/entity/PlayableEntity;"
private const val SESSION = "Landroid/media/session/MediaSession;"
private const val BRIDGE = "Ldev/chudpresence/revanced/HuluMetadataBridge;"

/** Only adds descriptive metadata to the existing session; playback and DRM remain stock. */
@Suppress("unused")
val exposeHuluMetadataPatch = bytecodePatch(
    name = "Expose Hulu media metadata",
    description = "Publishes the playing title, series, season/episode, duration and public artwork to Android media sessions."
) {
    compatibleWith("com.hulu.plus"("6.34.0+18552420-google"))
    extendWith("extensions/metadata-extension.rve")

    apply {
        val start = firstMethodComposite {
            predicate { definingClass == PRESENTER && name == "s" && returnType == "V" &&
                parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Z") }
        }.method
        val changed = firstMethodComposite {
            predicate { definingClass == PRESENTER && name == "s0" && returnType == "V" &&
                parameterTypes.singleOrNull()?.toString() == ENTITY }
        }.method
        val manager = firstMethodComposite {
            predicate { definingClass == MANAGER && name == "<init>" }
        }.method.immutableClassDef
        val startInfo = firstMethodComposite {
            predicate { definingClass == START_INFO && name == "<init>" }
        }.method.immutableClassDef

        // Fail closed if the verified field layout or scratch registers have changed.
        require(start.immutableClassDef.fields.any { it.name == "y" && it.type == MANAGER })
        require(start.immutableClassDef.fields.any { it.name == "d" && it.type == START_INFO })
        require(manager.fields.any { it.name == "a" && it.type == SESSION })
        require(startInfo.fields.any { it.name == "a" && it.type == ENTITY })
        require(start.implementation!!.registerCount - 3 >= 2) { "Hulu start hook needs two local registers" }
        require(changed.implementation!!.registerCount - 2 >= 2) { "Hulu item-change hook needs two local registers" }

        // v0/v1 are scratch locals at entry; from16 also supports high parameter registers.
        start.addInstructions(0, """
            move-object/from16 v0, p0
            iget-object v1, v0, $PRESENTER->d:$START_INFO
            iget-object v1, v1, $START_INFO->a:$ENTITY
            iget-object v0, v0, $PRESENTER->y:$MANAGER
            iget-object v0, v0, $MANAGER->a:$SESSION
            invoke-static {v0, v1}, $BRIDGE->publish($SESSION${"Ljava/lang/Object;"})V
        """)
        changed.addInstructions(0, """
            move-object/from16 v0, p0
            iget-object v0, v0, $PRESENTER->y:$MANAGER
            iget-object v0, v0, $MANAGER->a:$SESSION
            move-object/from16 v1, p1
            invoke-static {v0, v1}, $BRIDGE->publish($SESSION${"Ljava/lang/Object;"})V
        """)
    }
}
