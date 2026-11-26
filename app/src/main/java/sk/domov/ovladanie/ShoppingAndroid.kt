package sk.domov.ovladanie

import android.content.Context

/** Uloženie nákupného zoznamu a histórie pridaných vecí: v tomto telefóne (SharedPreferences), je súčasťou zálohy. Spoločný zoznam sa od 1.46 synchronizuje s domácnosťou; Môj (od 1.52) ostáva len v tomto telefóne. */
object ShoppingStore {
    const val PREFS = "domov_shopping"
    private const val KEY = "items"
    /** História pridaných vecí (od 1.43) – návrhy pri písaní a rýchle tlačidlá. */
    private const val HISTORY = "history"
    /** Zbalená sekcia „Často kupuješ“ (od 1.45; len nastavenie vzhľadu, nie je v zálohe). */
    private const val CHIPS_COLLAPSED = "chips_collapsed"
    /** Naposledy otvorený pohľad Nákupu: m / b / s (od 1.52, nie je v zálohe). */
    private const val VIEW = "view"
    /** Kam pole pridáva, keď sú otvorené oba zoznamy: m / s. */
    private const val TARGET = "target"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Všetky položky vrátane tombstone (pre synchronizáciu). */
    fun all(c: Context): List<ShopItem> = migrate(c, ShoppingList.fromJson(prefs(c).getString(KEY, null)))

    /** Živé položky v poradí, ako sa uložili (na obrazovke ich zoradí [ShoppingList.arrange]). */
    fun items(c: Context): List<ShopItem> = all(c).filter { !it.deleted }

    private fun migrate(c: Context, list: List<ShopItem>): List<ShopItem> {
        if (list.none { it.updatedAt == 0L }) return list
        val next = list.map { if (it.updatedAt == 0L) it.copy(updatedAt = maxOf(it.addedAt, it.doneAt, 1L)) else it }
        prefs(c).edit().putString(KEY, ShoppingList.toJson(next)).apply()
        return next
    }

    /** História; pri prvom spustení 1.43 sa naplní z terajšieho zoznamu. */
    @Synchronized
    fun history(c: Context): List<ShopHistoryEntry> {
        val p = prefs(c)
        if (!p.contains(HISTORY)) {
            val seeded = ShoppingHistory.seed(items(c))
            p.edit().putString(HISTORY, ShoppingHistory.toJson(seeded)).apply()
            return seeded
        }
        return ShoppingHistory.fromJson(p.getString(HISTORY, null))
    }

    @Synchronized
    private fun update(c: Context, change: (List<ShopItem>) -> List<ShopItem>): List<ShopItem> {
        val before = all(c)
        val next = change(before)
        prefs(c).edit().putString(KEY, ShoppingList.toJson(next)).apply()
        // widgety Nákup na ploche (od 1.44) ukazujú vždy aktuálny zoznam
        ShoppingWidget.redrawAll(c)
        DnesWidget.redrawAll(c)
        if (!HouseSyncEngine.applying) {
            val ids = next.filter { n -> before.find { it.id == n.id } != n }.map { it.id } +
                before.filter { b -> next.none { it.id == b.id } }.map { it.id }
            for (id in ids.distinct()) {
                val n = next.find { it.id == id }
                val b = before.find { it.id == id }
                // súkromné položky sa nikdy neposielajú; tombstone spoločnej áno
                if ((n != null && n.list == ShopListKind.SHARED) || (b != null && b.list == ShopListKind.SHARED)) {
                    HouseSyncEngine.onLocalChange(c, HouseSync.KIND_SHOP, id)
                }
            }
        }
        return next.filter { !it.deleted }
    }

    @Synchronized
    fun replaceAll(c: Context, list: List<ShopItem>) {
        val now = System.currentTimeMillis()
        val pruned = list.filter { !it.deleted || now - it.updatedAt < HouseSync.TOMBSTONE_MS }
        prefs(c).edit().putString(KEY, ShoppingList.toJson(pruned)).apply()
        ShoppingWidget.redrawAll(c)
        DnesWidget.redrawAll(c)
    }

    /**
     * Last-writer-wins zlúčenie príchodzích spoločných položiek s aktuálnym úložiskom v jednom zámku,
     * aby súbežné [update] nestratilo miestnu úpravu. Súkromná položka (MINE) s rovnakým id sa neprepisuje.
     */
    @Synchronized
    fun mergeIncoming(c: Context, incoming: List<ShopItem>): Boolean {
        if (incoming.isEmpty()) return false
        val keep = all(c).associateBy { it.id }.toMutableMap()
        var changed = false
        for (raw in incoming) {
            if (raw.id.isBlank()) continue
            val item = raw.copy(list = ShopListKind.SHARED)
            val existing = keep[item.id]
            if (existing != null && existing.list == ShopListKind.MINE) continue
            if (existing == null || HouseSync.incomingWins(existing.updatedAt, existing.deviceId, item.updatedAt, item.deviceId)) {
                if (existing != item) {
                    keep[item.id] = item
                    changed = true
                }
            }
        }
        if (!changed) return false
        val now = System.currentTimeMillis()
        val pruned = keep.values.filter { !it.deleted || now - it.updatedAt < HouseSync.TOMBSTONE_MS }
        prefs(c).edit().putString(KEY, ShoppingList.toJson(pruned)).apply()
        ShoppingWidget.redrawAll(c)
        DnesWidget.redrawAll(c)
        return true
    }

    /** Sledovanie zmien zoznamu (napr. odškrtnutie z widgetu, kým je obrazovka Nákup otvorená). Listener drž v pamäti. */
    fun listen(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)

    fun unlisten(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    @Synchronized
    private fun updateHistory(c: Context, change: (List<ShopHistoryEntry>) -> List<ShopHistoryEntry>) {
        val next = change(history(c))
        prefs(c).edit().putString(HISTORY, ShoppingHistory.toJson(next)).apply()
    }

    /** Pridá položku; do histórie sa zapíše, len keď sa zoznam naozaj zmenil (nekúpená duplicita sa nepočíta). */
    @Synchronized
    fun add(c: Context, text: String, list: ShopListKind = ShopListKind.SHARED, historyName: String? = null): List<ShopItem> {
        val before = items(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = update(c) { ShoppingList.add(it, text, now, addedById = me, addedByName = HouseholdStore.name(c), deviceId = me, list = list) }
        if (next != before) updateHistory(c) { ShoppingHistory.record(it, historyName?.takeIf { n -> n.isNotBlank() } ?: text, now) }
        return next
    }

    /** Názov do histórie návrhov: pri položke z jedálnička bez množstva („Cibuľa“ a nie „Cibuľa 450 g“). */
    private fun historyNameOf(c: Context, item: ShopItem): String {
        val fromPlan = runCatching { MealsStore.isPlanItem(c, item.id) }.getOrDefault(false)
        return if (fromPlan) Meals.parseAmount(item.name).name.ifBlank { item.name } else item.name
    }

    /**
     * Odškrtne / vráti položku. Od 1.45 sa čas odškrtnutia zapíše do histórie ako nákup (z toho sa počíta, kedy treba
     * kúpiť znova); vrátenie medzi nekúpené ten nákup zabudne.
     */
    @Synchronized
    fun toggle(c: Context, id: String): List<ShopItem> {
        val before = items(c).firstOrNull { it.id == id }
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = update(c) { ShoppingList.toggle(it, id, now).map { if (it.id == id) it.copy(deviceId = me) else it } }
        if (before != null) {
            if (!before.done) {
                updateHistory(c) { ShoppingHistory.bought(it, historyNameOf(c, before), now) }
                // od 1.60: odškrtnutá položka ide do zásob (množstvo z názvu, bez pýtania)
                runCatching { MealsStore.addPurchased(c, before.name) }
            } else if (before.doneAt > 0) updateHistory(c) { ShoppingHistory.unbought(it, historyNameOf(c, before), before.doneAt) }
        }
        return next
    }

    /** Je sekcia „Často kupuješ“ zbalená (pamätá sa, platí pre obrazovku Nákup aj rýchle pridanie)? */
    fun chipsCollapsed(c: Context): Boolean = prefs(c).getBoolean(CHIPS_COLLAPSED, false)

    fun setChipsCollapsed(c: Context, collapsed: Boolean) = prefs(c).edit().putBoolean(CHIPS_COLLAPSED, collapsed).apply()

    fun clearDone(c: Context, kind: ShopListKind? = null): List<ShopItem> =
        update(c) { ShoppingList.tombstoneDone(it, System.currentTimeMillis(), HouseholdStore.phoneId(c), kind) }

    /** Presunie položku do druhého zoznamu (spoločná → súkromná pošle tombstone, naopak začne synchronizáciu). */
    @Synchronized
    fun move(c: Context, item: ShopItem): List<ShopItem> {
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        return update(c) { ShoppingList.move(it, item.id, now, deviceId = me) }
    }

    fun view(c: Context): ShopView = ShopView.from(prefs(c).getString(VIEW, "b"))

    fun setView(c: Context, view: ShopView) {
        val code = when (view) {
            ShopView.MINE -> "m"
            ShopView.SHARED -> "s"
            ShopView.BOTH -> "b"
        }
        prefs(c).edit().putString(VIEW, code).apply()
    }

    fun target(c: Context): ShopListKind = ShopListKind.from(prefs(c).getString(TARGET, "s"))

    fun setTarget(c: Context, kind: ShopListKind) = prefs(c).edit().putString(TARGET, kind.code).apply()

    /** Odstráni položku. Nekúpená sa aj odpočíta z histórie (asi preklep alebo omyl). */
    @Synchronized
    fun remove(c: Context, item: ShopItem): List<ShopItem> {
        val next = update(c) { ShoppingList.tombstone(it, item.id, System.currentTimeMillis(), HouseholdStore.phoneId(c)) }
        if (!item.done) updateHistory(c) { ShoppingHistory.forget(it, historyNameOf(c, item)) }
        return next
    }

    /** „Späť“ po odstránení. */
    @Synchronized
    fun restore(c: Context, item: ShopItem): List<ShopItem> {
        val before = items(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = update(c) { ShoppingList.restore(it, item.copy(deleted = false, updatedAt = now, deviceId = me)) }
        if (next != before && !item.done) updateHistory(c) { ShoppingHistory.record(it, item.name, item.addedAt) }
        return next
    }

    /** Premenuje položku a prenesie jej históriu na nový názov. */
    @Synchronized
    fun rename(c: Context, item: ShopItem, text: String): List<ShopItem> {
        val before = items(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = update(c) {
            ShoppingList.rename(it, item.id, text).map { s -> if (s.id == item.id) s.copy(updatedAt = now, deviceId = me) else s }
        }
        if (next != before) updateHistory(c) { ShoppingHistory.rename(it, item.name, text, System.currentTimeMillis()) }
        return next
    }

    /** Odstráni vec z návrhov a rýchlych tlačidiel (zoznam sa nemení). */
    fun forgetSuggestion(c: Context, name: String) = updateHistory(c) { ShoppingHistory.delete(it, name) }
}
