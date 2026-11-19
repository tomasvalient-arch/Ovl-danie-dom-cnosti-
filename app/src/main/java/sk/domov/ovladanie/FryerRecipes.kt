package sk.domov.ovladanie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uložené vlastné recepty fritézy (SharedPreferences „domov_recipes“, kľúč `recipes_<id zariadenia>`, JSON pole).
 * Ide aj do zálohy (BackupSpec). Poškodený záznam sa preskočí, nikdy nezhodí appku.
 */
object RecipeStore {
    private fun prefs(context: Context) = context.getSharedPreferences("domov_recipes", Context.MODE_PRIVATE)

    fun list(context: Context, deviceId: String): List<FryerRecipe> {
        val raw = prefs(context).getString("recipes_$deviceId", null) ?: return emptyList()
        return parse(raw)
    }

    fun save(context: Context, deviceId: String, recipes: List<FryerRecipe>) {
        prefs(context).edit().putString("recipes_$deviceId", toJson(recipes)).apply()
    }

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    internal fun toJson(recipes: List<FryerRecipe>): String = JSONArray(recipes.map {
        JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode).put("minutes", it.minutes).put("temp", it.tempC)
    }).toString()

    internal fun parse(raw: String): List<FryerRecipe> = try {
        val arr = JSONArray(raw)
        val out = ArrayList<FryerRecipe>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = FryerRecipes.cleanName(o.optString("name"))
            if (name.isEmpty()) continue
            val id = o.optString("id").ifBlank { newId() }
            if (out.any { it.id == id } || FryerRecipes.find(out, name) != null) continue
            out.add(
                FryerRecipe(
                    id = id,
                    name = name,
                    mode = o.optString("mode"),
                    minutes = o.optInt("minutes", Fryer.TIME_MIN).coerceIn(Fryer.TIME_MIN, Fryer.TIME_MAX),
                    tempC = FryerRecipes.snapTemp(o.optInt("temp", Fryer.TEMP_MAX)),
                ),
            )
            if (out.size >= FryerRecipes.MAX_RECIPES) break
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}
