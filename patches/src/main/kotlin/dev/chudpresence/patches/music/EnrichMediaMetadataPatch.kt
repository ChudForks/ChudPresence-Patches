package dev.chudpresence.patches.music

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.predicate
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val MEDIA_SESSION = "Landroid/media/session/MediaSession;"
private const val MEDIA_METADATA = "Landroid/media/MediaMetadata;"
private const val EXTENSION_CLASS = "Ldev/chudpresence/revanced/MetadataEnricher;"

/**
 * Replaces YouTube Music's platform MediaSession.setMetadata call with a small wrapper.
 * The wrapper publishes the original metadata immediately, then republishes an enriched
 * copy after resolving structured track information away from the main thread.
 */
@Suppress("unused")
val enrichMediaMetadataPatch = bytecodePatch(
    name = "Enrich media metadata",
    description = "Adds song, artist and album links, album metadata and public artwork to YouTube Music's media session."
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))

    extendWith("extensions/metadata-extension.rve")

    apply {
        hookMediaSession("setMetadata")
        // These playback-stage accessors were verified against the stock 8.40.54 APK.
        // H() reads the video ID; G() reads the title from the same video-details object.
        // Pin compatibility because these two obfuscated accessors are version-specific.
        val stage = firstMethodComposite {
            predicate {
                name == "handleVideoStageEvent" && immutableClassDef.methods.any { it.name == "handleLikeVideoActionEvent" }
            }
        }.method
        require(stage.implementation!!.registerCount - stage.parameterTypes.size - 1 >= 3)
        stage.addInstructions(0, """
            iget-object v0, p1, Laxbt;->b:Laobl;
            if-eqz v0, :chud_presence_no_video
            invoke-interface {v0}, Laobl;->H()Ljava/lang/String;
            move-result-object v1
            invoke-interface {v0}, Laobl;->G()Ljava/lang/String;
            move-result-object v2
            invoke-static {v1, v2}, $EXTENSION_CLASS->onMusicVideoDetails(Ljava/lang/String;Ljava/lang/String;)V
            :chud_presence_no_video
            nop
        """)
    }
}

internal fun BytecodePatchContext.hookMediaSession(methodName: String) {
        val wrapper = "$EXTENSION_CLASS->$methodName(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
        val match = firstMethodComposite {
            // Exclude the extension's own platform call to avoid wrapping it recursively.
            predicate { !definingClass.startsWith("Ldev/chudpresence/revanced/") }
            instructions(
                method {
                    definingClass == MEDIA_SESSION &&
                        name == "setMetadata" &&
                        returnType == "V" &&
                        parameterTypes.singleOrNull()?.toString() == MEDIA_METADATA
                }
            )
        }

        val callIndex = match[0]
        val call: Instruction = match.method.getInstruction(callIndex)
        val replacement = when (call) {
            is FiveRegisterInstruction ->
                "invoke-static { v${call.registerC}, v${call.registerD} }, $wrapper"

            is RegisterRangeInstruction -> {
                require(call.registerCount == 2) { "Unexpected MediaSession.setMetadata register count" }
                "invoke-static/range { v${call.startRegister} .. v${call.startRegister + 1} }, $wrapper"
            }

            else -> error("Unsupported MediaSession.setMetadata invocation: ${call.opcode}")
        }

        match.method.replaceInstruction(callIndex, replacement)
}
