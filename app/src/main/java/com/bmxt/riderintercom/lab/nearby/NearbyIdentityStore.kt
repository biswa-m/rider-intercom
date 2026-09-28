package com.bmxt.riderintercom.lab.nearby

import android.content.Context
import java.util.UUID

class NearbyIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("nearby_lab_identity", Context.MODE_PRIVATE)
    val stableId: String
        get() = prefs.getString("stable_id", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("stable_id", it).apply()
        }

    val localName: String
        get() = "Rider-${android.os.Build.MODEL.ifBlank { "Android" }}-$stableId"
}
