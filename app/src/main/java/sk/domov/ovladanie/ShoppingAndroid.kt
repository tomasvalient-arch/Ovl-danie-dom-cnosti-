package sk.domov.ovladanie

import android.content.Context

/** Uloženie nákupného zoznamu: len v tomto telefóne (SharedPreferences), je súčasťou zálohy. */
object ShoppingStore {
    const val PREFS = "domov_shopping"
    private const val KEY = "items"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Všetky položky v poradí, ako sa uložili (na obrazovke ich zoradí [ShoppingList.arrange]). */
    fun items(c: Context): List<ShopItem> = ShoppingList.fromJson(prefs(c).getString(KEY, null))

    @Synchronized
    private fun update(c: Context, change: (List<ShopItem>) -> List<ShopItem>): List<ShopItem> {
        val next = change(items(c))
        prefs(c).edit().putString(KEY, ShoppingList.toJson(next)).apply()
        return next
    }

    fun add(c: Context, text: String): List<ShopItem> = update(c) { ShoppingList.add(it, text, System.currentTimeMillis()) }

    fun toggle(c: Context, id: String): List<ShopItem> = update(c) { ShoppingList.toggle(it, id, System.currentTimeMillis()) }

    fun clearDone(c: Context): List<ShopItem> = update(c) { ShoppingList.clearDone(it) }
}
