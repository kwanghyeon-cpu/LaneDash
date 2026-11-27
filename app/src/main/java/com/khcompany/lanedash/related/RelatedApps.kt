package com.khcompany.lanedash.related

import org.json.JSONObject

/**
 * "함께 즐기면 좋은 게임": KH Company's shared sibling-app list (kwanghyeon-cpu.github.io/related-apps.json),
 * shared by every app and kept in the portfolio repo. Pure rules live here; fetching and opening are in
 * [RelatedAppsRepository]. The web build follows the same rules in web/js/related.js.
 */
data class RelatedApp(
    val id: String,
    val name: String,
    val tagline: String,
    val packageName: String?,
    val isPublic: Boolean,
    val web: String?,
    val iconUrl: String?,
)

data class RelatedCatalog(
    /** Group id → app ids, in the file's order. */
    val groups: List<Pair<String, List<String>>>,
    val apps: Map<String, RelatedApp>,
)

/** Where tapping a sibling goes when it isn't installed. */
sealed interface RelatedLink {
    data class Store(val packageName: String) : RelatedLink {
        val marketUri get() = "market://details?id=$packageName"
        val webUri get() = "https://play.google.com/store/apps/details?id=$packageName"
    }
    data class Web(val url: String) : RelatedLink
}

object RelatedApps {
    const val SELF_ID = "lanedash"
    const val LIST_URL = "https://kwanghyeon-cpu.github.io/related-apps.json"
    const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun parse(json: String): RelatedCatalog {
        val root = JSONObject(json)
        val groups = mutableListOf<Pair<String, List<String>>>()
        root.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val ids = g.optJSONArray("apps") ?: continue
                groups += g.optString("id") to (0 until ids.length()).map { ids.optString(it) }
            }
        }
        val apps = mutableMapOf<String, RelatedApp>()
        root.optJSONObject("apps")?.let { obj ->
            for (id in obj.keys()) {
                val a = obj.optJSONObject(id) ?: continue
                apps[id] = RelatedApp(
                    id = id,
                    name = a.optString("name", id),
                    tagline = a.optString("tagline"),
                    packageName = a.stringOrNull("package"),
                    isPublic = a.optBoolean("public", false),
                    web = a.stringOrNull("web"),
                    iconUrl = a.stringOrNull("icon"),
                )
            }
        }
        return RelatedCatalog(groups, apps)
    }

    /** Other apps from every group this app is in, in order, without repeats or apps that have nowhere to go. */
    fun siblings(catalog: RelatedCatalog, selfId: String = SELF_ID): List<RelatedApp> =
        catalog.groups
            .filter { (_, ids) -> selfId in ids }
            .flatMap { (_, ids) -> ids }
            .distinct()
            .filter { it != selfId }
            .mapNotNull { catalog.apps[it] }
            .filter { linkFor(it) != null }

    /** Public apps go to the Play Store; unreleased ones to their web build; neither → not shown. */
    fun linkFor(app: RelatedApp): RelatedLink? = when {
        app.isPublic && app.packageName != null -> RelatedLink.Store(app.packageName)
        app.web != null -> RelatedLink.Web(app.web)
        else -> null
    }

    /** At most one download a day (a clock moved backwards also refreshes). */
    fun shouldRefresh(lastFetchMs: Long, nowMs: Long): Boolean =
        lastFetchMs <= 0L || nowMs < lastFetchMs || nowMs - lastFetchMs >= REFRESH_INTERVAL_MS

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
