package dev.chudpresence.patches.youtube

import app.revanced.patcher.*
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.chudpresence.patches.music.hookMediaSession

private const val EXTENSION = "Ldev/chudpresence/revanced/MetadataEnricher;"

/** Playing-ID fingerprints follow ReVanced's videoid/playerresponse hooks (GPLv3). */
@Suppress("unused")
val exposeVideoMetadataPatch = bytecodePatch(
    name = "Expose YouTube media metadata",
    description = "Exposes the playing video, creator, artwork and Shorts links to Android media-session consumers."
) {
    compatibleWith("com.google.android.youtube"("20.40.45"))
    extendWith("extensions/metadata-extension.rve")
    apply {
        hookMediaSession("setYouTubeMetadata")

        val parent = firstMethodComposite {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("[L")
            parameterTypes("L")
            instructions(524288L())
        }
        val foreground = parent.immutableClassDef.firstMethodComposite {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("V")
            parameterTypes("L")
            instructions(
                allOf(Opcode.INVOKE_INTERFACE(), method { returnType == "Ljava/lang/String;" }),
                after(Opcode.MOVE_RESULT_OBJECT()),
                afterAtMost(6, method { toString() == "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
                after(Opcode.RETURN_VOID())
            )
        }
        val foregroundIndex = foreground[1]
        val foregroundRegister = foreground.method.getInstruction<OneRegisterInstruction>(foregroundIndex).registerA
        foreground.method.addInstruction(foregroundIndex + 1, "invoke-static {v$foregroundRegister}, $EXTENSION->onVideoId(Ljava/lang/String;)V")

        val background = firstMethodComposite {
            accessFlags(AccessFlags.DECLARED_SYNCHRONIZED, AccessFlags.FINAL, AccessFlags.PUBLIC)
            returnType("V")
            parameterTypes("L")
            instructions(
                method { returnType == "Ljava/lang/String;" }, Opcode.MOVE_RESULT_OBJECT(),
                Opcode.IPUT_OBJECT(), Opcode.MONITOR_EXIT(), Opcode.RETURN_VOID(),
                Opcode.MONITOR_EXIT(), Opcode.RETURN_VOID()
            )
            custom { immutableClassDef.methods.count() in 16..17 }
        }
        val backgroundIndex = background[0] + 1
        val backgroundRegister = background.method.getInstruction<OneRegisterInstruction>(backgroundIndex).registerA
        background.method.addInstruction(backgroundIndex + 1, "invoke-static {v$backgroundRegister}, $EXTENSION->onVideoId(Ljava/lang/String;)V")

        val parameters = firstMethodComposite {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("L")
            parameterTypes("Ljava/lang/String;", "[B", "Ljava/lang/String;", "Ljava/lang/String;",
                "I", "Z", "I", "L", "Ljava/util/Set;", "Ljava/lang/String;", "Ljava/lang/String;",
                "L", "Z", "Z", "Z", "Lj$/time/Duration;")
            predicate {
                implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "psps"
                } == true
            }
        }.method
        require(parameters.implementation!!.registerCount - parameters.parameterTypes.size - 1 >= 3) {
            "Player parameter hook requires three scratch registers"
        }
        parameters.addInstructions(0, """
            move-object/from16 v0, p1
            move-object/from16 v1, p3
            move/from16 v2, p13
            invoke-static {v0, v1, v2}, $EXTENSION->onPlayerParameters(Ljava/lang/String;Ljava/lang/String;Z)V
        """)
    }
}
