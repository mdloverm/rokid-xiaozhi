package com.rokid.xiaozhi

data class AppStoreItem(
    val name: String,
    val displayName: String,
    val url: String,
    val packageName: String,
    val description: String
)

data class AppStoreConfig(
    val apps: List<AppStoreItem> = emptyList()
)
