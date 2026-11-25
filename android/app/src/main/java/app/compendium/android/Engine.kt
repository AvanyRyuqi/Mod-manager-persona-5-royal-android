package app.compendium.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class EngineException(message: String, val cancelled: Boolean = false) : Exception(message)

/** Builds a JSONObject; lists become JSON arrays (org.json would store them as text). */
fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, if (v is Collection<*>) JSONArray(v) else v)
    return o
}

/** Calls into the native engine on a background thread. */
object Engine {
    private suspend fun raw(method: String, args: JSONObject, task: String?): Any? = withContext(Dispatchers.IO) {
        if (task != null) args.put("task", task)
        try {
            val out = Native.call(method.toByteArray(Charsets.UTF_8), args.toString().toByteArray(Charsets.UTF_8))
            val r = JSONObject(String(out, Charsets.UTF_8))
            if (!r.optBoolean("ok")) throw EngineException(r.optString("error", "Something went wrong"), r.optBoolean("cancelled"))
            r.opt("data")
        } finally {
            if (task != null) Bridge.clearProgress(task)
        }
    }

    suspend fun call(method: String, args: JSONObject = JSONObject(), task: String? = null): JSONObject =
        raw(method, args, task) as? JSONObject ?: JSONObject()

    suspend fun callArray(method: String, args: JSONObject = JSONObject()): JSONArray =
        raw(method, args, null) as? JSONArray ?: JSONArray()

    fun cancel(task: String) = Native.cancel(task.toByteArray(Charsets.UTF_8))
}
