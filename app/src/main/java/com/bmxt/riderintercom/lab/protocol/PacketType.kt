package com.bmxt.riderintercom.lab.protocol

enum class PacketType(val code: Byte) {
    TEST(1),
    VOICE(2),
    CONTROL(3);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}
