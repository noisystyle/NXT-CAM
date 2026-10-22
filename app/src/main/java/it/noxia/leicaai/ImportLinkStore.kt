package it.noxia.leicaai

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** Keeps the non-destructive link between an imported gallery image and its NXT result. */
class ImportLinkStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun link(resultUri: Uri, originalUri: Uri) = synchronized(LOCK) {
        val links = readLinks()
        links.put(resultUri.toString(), originalUri.toString())
        preferences.edit().putString(KEY_LINKS, links.toString()).commit()
    }

    fun originalFor(resultUri: Uri): Uri? = synchronized(LOCK) {
        readLinks().optString(resultUri.toString())
            .takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
    }

    fun remove(resultUri: Uri) = synchronized(LOCK) {
        val links = readLinks()
        links.remove(resultUri.toString())
        val normalized = preferences.getStringSet(KEY_NORMALIZED_DATES, emptySet()).orEmpty()
            .toMutableSet()
            .apply { remove(resultUri.toString()) }
        preferences.edit()
            .putString(KEY_LINKS, links.toString())
            .putStringSet(KEY_NORMALIZED_DATES, normalized)
            .commit()
    }

    fun isDateNormalized(resultUri: Uri): Boolean =
        resultUri.toString() in preferences.getStringSet(KEY_NORMALIZED_DATES, emptySet()).orEmpty()

    fun markDateNormalized(resultUri: Uri) = synchronized(LOCK) {
        val normalized = preferences.getStringSet(KEY_NORMALIZED_DATES, emptySet()).orEmpty()
            .toMutableSet()
            .apply { add(resultUri.toString()) }
        preferences.edit().putStringSet(KEY_NORMALIZED_DATES, normalized).commit()
    }

    private fun readLinks(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_LINKS, null) ?: "{}")
    }.getOrDefault(JSONObject())

    private companion object {
        val LOCK = Any()
        const val PREFERENCES = "next_camera_import_links"
        const val KEY_LINKS = "links"
        const val KEY_NORMALIZED_DATES = "normalized_dates"
    }
}
