package dev.chudpresence.patches.music

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.predicate
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val MEDIA_SESSION = "Landroid/media/session/MediaSession;"
private const val MEDIA_METADATA = "Landroid/media/MediaMetadata;"
private const val EXTENSION_CLASS = "Ldev/chudpresence/revanced/MetadataEnricher;"
private const val WRAPPER =
    "$EXTENSION_CLASS->setMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"

/**
 * Replaces YouTube Music's platform MediaSession.setMetadata call with a small wrapper.
 * The wrapper publishes the original metadata immediately, then republishes an enriched
 * copy after resolving structured track information away from the main thread.
 */
@Suppress("unused")
val enrichMediaMetadataPatch = bytecodePatch(
    name = "Enrich media metadata",
    description = "Adds album, album artist, and year to YouTube Music's Android media session."
) {
    compatibleWith("com.google.android.apps.youtube.music")

    extendWith("extensions/metadata-extension.rve")

    apply {
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
                "invoke-static { v${call.registerC}, v${call.registerD} }, $WRAPPER"

            is RegisterRangeInstruction -> {
                require(call.registerCount == 2) { "Unexpected MediaSession.setMetadata register count" }
                "invoke-static/range { v${call.startRegister} .. v${call.startRegister + 1} }, $WRAPPER"
            }

            else -> error("Unsupported MediaSession.setMetadata invocation: ${call.opcode}")
        }

        match.method.replaceInstruction(callIndex, replacement)
    }
}
