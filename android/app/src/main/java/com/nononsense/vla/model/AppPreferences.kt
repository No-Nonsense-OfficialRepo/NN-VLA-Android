package com.nononsense.vla.model

import android.content.Context

object AppPreferences {
    private const val PREFERENCES = "vla_preferences"
    private const val LAST_ROOM_KEY = "last_room_key"
    private const val SHARE_PHONE_FILES = "share_phone_files"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveRoomKey(context: Context, roomKey: RoomKey) {
        preferences(context).edit().putString(LAST_ROOM_KEY, roomKey.value).apply()
    }

    fun roomKey(context: Context): RoomKey? = RoomKey.parse(
        preferences(context).getString(LAST_ROOM_KEY, "").orEmpty(),
    ).getOrNull()

    fun isFileSharingEnabled(context: Context): Boolean =
        preferences(context).getBoolean(SHARE_PHONE_FILES, false)

    fun setFileSharingEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(SHARE_PHONE_FILES, enabled).apply()
    }

}
