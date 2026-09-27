package com.bmxt.riderintercom.intercom.audio

/** Packet decoded from the UDP wire format before the selected receive pipeline handles it. */
sealed interface ReceivedVoicePacket {
    data class Pcm(val packet: PcmVoicePacket, val packetBytes: Int) : ReceivedVoicePacket
    data class Opus(val packet: OpusVoicePacket, val packetBytes: Int) : ReceivedVoicePacket
}
