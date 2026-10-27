package eu.darken.capod.pods.core.apple.aap.protocol

import eu.darken.capod.reaction.core.headgesture.HeadMotion
import eu.darken.capod.reaction.core.headgesture.HeadTrackingSample

object HeadMotionCodec {
    fun decode(raw: ByteArray): HeadMotion? = BuddySensorCodec.decodeMotion(raw)
    fun decodeSample(raw: ByteArray, receivedAtMs: Long): HeadTrackingSample? = BuddySensorCodec.decodeHead(raw, receivedAtMs)

}
