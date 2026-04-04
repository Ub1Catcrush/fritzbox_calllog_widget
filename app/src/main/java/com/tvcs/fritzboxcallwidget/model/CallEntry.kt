package com.tvcs.fritzboxcallwidget.model

import java.time.LocalDateTime

enum class CallType {
    INCOMING,   // Eingehend / angenommen
    OUTGOING,   // Ausgehend
    MISSED      // Verpasst / abgewiesen
}

data class CallEntry(
    val date: LocalDateTime,
    val type: CallType,
    val name: String?,      // Kontaktname aus der FritzBox, kann null sein
    val number: String,     // Telefonnummer (ggf. mit Präfix)
    val duration: Int = 0   // Gesprächsdauer in Sekunden
) {
    /** Zeigt den Namen – oder falls keiner vorhanden, die Nummer. */
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else number
}
