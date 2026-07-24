package com.naomi.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistent key→value fact store (naomi_facts.json in internal storage).
 * Keys are normalised to lowercase so "Mom", "mom", "MOM" all resolve the same way.
 *
 * Typical entries: {"mom":"Amma","dad":"Papa","home":"HSR Layout"}
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "naomi_facts.json")
    private val facts = mutableMapOf<String, String>()

    init {
        load()
    }

    /** Store a fact. Both key and value are trimmed; key is lowercased. */
    fun put(key: String, value: String) {
        facts[key.trim().lowercase()] = value.trim()
        save()
    }

    /** Return the stored value for [key], or null if unknown. */
    fun get(key: String): String? = facts[key.trim().lowercase()]

    /** Delete a fact by key. No-op if it doesn't exist. */
    fun remove(key: String) {
        if (facts.remove(key.trim().lowercase()) != null) save()
    }

    /**
     * Edit an existing fact. If the key changed, the old entry is removed.
     * Used by the Facts screen's inline editor.
     */
    fun update(oldKey: String, newKey: String, value: String) {
        val ok = oldKey.trim().lowercase()
        val nk = newKey.trim().lowercase()
        if (ok != nk) facts.remove(ok)
        facts[nk] = value.trim()
        save()
    }

    /** All stored facts as a display string, one per line. */
    fun all(): Map<String, String> = facts.toMap()

    /**
     * Resolves a contact name spoken by the user.
     * If [name] itself is a stored key (e.g. "mom"), returns the mapped value ("Amma").
     * Otherwise returns [name] unchanged so normal contact lookup proceeds.
     */
    fun resolveContact(name: String): String {
        val lower = name.trim().lowercase()
        // Direct match: "mom" → "Amma"
        facts[lower]?.let { return it }
        // Prefix match: "my mom" → strip "my " → "Amma"
        val stripped = lower.removePrefix("my ").removePrefix("the ")
        facts[stripped]?.let { return it }
        return name
    }

    /**
     * Tries to parse a spoken fact statement into a key→value pair and stores it.
     * Patterns handled:
     *   "my mom is Amma"            → mom → Amma
     *   "my mom's contact is Amma"  → mom → Amma
     *   "mom is saved as Amma"      → mom → Amma
     *   "remember mom is Amma"      → mom → Amma
     * Returns a confirmation string to speak, or null if the text didn't match.
     */
    fun learnFromSpeech(text: String): String? {
        val t = text.trim()

        // "my X is Y" / "my X's contact is Y"
        Regex(
            """^(?:remember\s+)?my\s+(.+?)(?:'s\s+(?:contact|number|name))?\s+is\s+(?:saved\s+as\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(t)?.let {
            val key = it.groupValues[1].trim().lowercase()
            val value = it.groupValues[2].trim()
            put(key, value)
            return "Got it. I'll remember your $key is $value."
        }

        // "X is saved as Y"
        Regex(
            """^(.+?)\s+is\s+saved\s+as\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(t)?.let {
            val key = it.groupValues[1].trim().lowercase()
            val value = it.groupValues[2].trim()
            put(key, value)
            return "Got it. I'll remember $key is $value."
        }

        // "remember X is Y"
        Regex(
            """^remember\s+(?:that\s+)?(.+?)\s+is\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(t)?.let {
            val key = it.groupValues[1].trim().lowercase()
            val value = it.groupValues[2].trim()
            put(key, value)
            return "Got it. I'll remember $key is $value."
        }

        return null
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            json.keys().forEach { k -> facts[k] = json.getString(k) }
        } catch (_: Exception) {}
    }

    private fun save() {
        val json = JSONObject()
        facts.forEach { (k, v) -> json.put(k, v) }
        file.writeText(json.toString(2))
    }
}
