package com.tvcs.fritzboxcallwidget.model

import java.time.LocalDateTime

enum class CallType {
    INCOMING,        // 1, 4  — eingehend, angenommen
    OUTGOING,        // 3     — ausgehend
    MISSED,          // 2     — verpasst
    BLOCKED,         // 10    — abgewiesen / Rufsperre
    VOICEMAIL,       // 1, 4 auf AB-Port (Port ≥ 40) — Nachricht auf AB hinterlassen
    FAX_RECEIVED,    // 1, 4 mit numbertype "fax" — eingehendes Fax
    FAX_SENT,        // 3    mit numbertype "fax" — ausgehendes Fax
    ACTIVE_INCOMING, // 9    — Anruf gerade aktiv (eingehend, noch nicht beendet)
    ACTIVE_OUTGOING, // 11   — Anruf gerade aktiv (ausgehend, noch nicht beendet)
}

data class CallEntry(
    val date: LocalDateTime,
    val type: CallType,
    val name: String?,
    val number: String,
    val duration: Int = 0
) {
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else number
}
