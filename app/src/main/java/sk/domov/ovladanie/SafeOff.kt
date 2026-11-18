package sk.domov.ovladanie

/**
 * Bezpečné hromadné vypínanie (Všetko vypnúť, odchod, Noc, test odchodu).
 *
 * Pravidlá (od 1.9.3, po chybe, keď „Všetko vypnúť“ spustilo fritézu BH-AF2):
 * - hromadná akcia nikdy nepošle príkaz, ktorý by mohol fritézu, ohrievač alebo neznáme zariadenie zapnúť,
 * - nič sa neprepína a neposiela sa hodnota, ktorú zariadenie už má (niektoré čipy berú zápis hlavného vypínača
 *   ako stlačenie tlačidla – fritéza BH-AF2 sa tak zapla a spustila program),
 * - fritéza: jediný povolený príkaz je Stop (štart/stop = false), a len keď čerstvo prečítaný stav ukazuje, že varí,
 * - ohrievač, termostat, neznáme zariadenie: len istý hlavný vypínač (ručne nastavený, svetlo alebo kód zo schémy),
 *   len keď je čerstvo prečítaný a zapnutý; inak „preskočené“,
 * - po odoslaní sa stav overí; ak sa niečo zaplo, hneď sa pošle Stop / vypnutie a zobrazí sa varovanie.
 */
sealed class OffStep {
    abstract val device: Device

    /** Poslať príkaz. */
    data class Send(override val device: Device, val cmd: Map<String, Any>) : OffStep()

    /** Nič netreba (už je vypnuté / nevarí). */
    data class Idle(override val device: Device) : OffStep()

    /** Preskočené z bezpečnostných dôvodov (neistý stav alebo vypínač). */
    data class Skip(override val device: Device, val reason: String) : OffStep()
}

/** Cloud vypnutie: alternatívy príkazov (kódy), alebo [skip] = dôvod preskočenia; obe prázdne = netreba nič. */
data class CloudOffPlan(val options: List<List<Pair<String, Any>>>, val skip: String?)

/** Výsledok overenia po odoslaní: príkaz na nápravu a varovanie, alebo null, ak je všetko v poriadku. */
data class OffRescue(val cmd: Map<String, Any>, val warning: String)

object SafeOff {
    const val SKIP_UNKNOWN = "stav neznámy"
    const val SKIP_NO_STOP = "nie je istý príkaz na zastavenie"
    const val SKIP_NO_SWITCH = "nie je istý vypínač"

    private val FRYER_NAME = Regex("(?i)(frit[eé]z|fryer|air ?fryer|\\bBH-AF\\d|\\bAF\\d{1,4}\\b)")

    /** Fritéza podľa hodnôt alebo podľa názvu/modelu (aj keď vypnutá neposlala všetky hodnoty). */
    fun looksLikeFryer(d: Device, values: Map<String, Any>): Boolean =
        Fryer.detect(values) != null || d.category.trim().equals("kqzg", ignoreCase = true) ||
            FRYER_NAME.containsMatchIn(d.name) || FRYER_NAME.containsMatchIn(d.model)

    /** Zariadenie, pri ktorom môže nesprávny príkaz spustiť ohrev alebo niečo nečakané. */
    fun risky(d: Device, values: Map<String, Any>): Boolean {
        if (looksLikeFryer(d, values)) return true
        if (AutoDevices.hotCategory(d.category)) return true
        return when (AutoDevices.kind(d, values)) {
            AutoKind.LIGHT, AutoKind.SOCKET, AutoKind.COVER -> false
            else -> true
        }
    }

    /**
     * Istý hlavný vypínač: ručne nastavený, vypínač svetla alebo kód zo schémy (switch, switch_1…).
     * Nikdy „prvá zapnutá/vypnutá hodnota“ a nikdy štart/stop fritézy.
     */
    fun certainPower(d: Device, values: Map<String, Any>): String? {
        if (looksLikeFryer(d, values)) return null
        if (d.powerDp.isNotBlank() && values[d.powerDp] is Boolean) return d.powerDp
        Light.detect(values)?.let { return it.power }
        return Schema.powerDp(d.schema, values)
    }

    /**
     * Krok hromadného vypnutia. [fresh] = hodnoty boli práve prečítané zo zariadenia (nie z pamäte).
     * Rizikové zariadenia bez čerstvého stavu sa preskočia.
     */
    fun offStep(d: Device, values: Map<String, Any>, fresh: Boolean): OffStep {
        if (looksLikeFryer(d, values)) return fryerStep(d, values, fresh)
        val kind = AutoDevices.kind(d, values)
        if (!AutoDevices.canDepart(kind)) return OffStep.Idle(d)
        if (risky(d, values)) {
            if (!fresh) return OffStep.Skip(d, SKIP_UNKNOWN)
            val dp = certainPower(d, values) ?: return OffStep.Skip(d, SKIP_NO_SWITCH)
            return when (values[dp]) {
                true -> OffStep.Send(d, mapOf(dp to false))
                false -> OffStep.Idle(d)
                else -> OffStep.Skip(d, SKIP_UNKNOWN)
            }
        }
        val cmd = Scenes.offCommand(d, values) ?: return OffStep.Idle(d)
        // hodnoty, ktoré už zariadenie má, sa neposielajú (zápis rovnakej hodnoty nie je potrebný)
        val needed = if (fresh) cmd.filter { (k, v) -> !Scenes.sameValue(values[k], v) } else cmd
        return if (needed.isEmpty()) OffStep.Idle(d) else OffStep.Send(d, needed)
    }

    /** Fritéza: len Stop, len keď naozaj varí. */
    fun fryerStep(d: Device, values: Map<String, Any>, fresh: Boolean): OffStep {
        if (!fresh) return OffStep.Skip(d, SKIP_UNKNOWN)
        val spec = Fryer.detect(values) ?: return OffStep.Skip(d, SKIP_NO_STOP)
        return when (values[spec.start]) {
            true -> OffStep.Send(d, Fryer.start(spec, false))
            false -> OffStep.Idle(d)
            else -> OffStep.Skip(d, SKIP_UNKNOWN)
        }
    }

    /** Je fritéza podľa stavu v prevádzke (štart/stop zapnuté alebo stav „varí“)? */
    fun fryerRunning(values: Map<String, Any>): Boolean {
        val spec = Fryer.detect(values) ?: return false
        if (values[spec.start] == true) return true
        return Fryer.statusLabel(values[spec.status] as? String ?: "") == "Varí"
    }

    /**
     * Overenie po hromadnej akcii: [sent] = čo appka poslala (môže byť prázdne), [after] = čerstvý stav.
     * Fritéza, ktorá varí → Stop. Hodnota, ktorú sme vypínali a je zapnutá → znova vypnúť. Inak null.
     */
    fun verify(d: Device, sent: Map<String, Any>, before: Map<String, Any>, after: Map<String, Any>): OffRescue? {
        if (looksLikeFryer(d, after) || looksLikeFryer(d, before)) {
            val spec = Fryer.detect(after) ?: Fryer.detect(before) ?: return null
            if (fryerRunning(after) && (sent.isNotEmpty() || !fryerRunning(before))) {
                return OffRescue(Fryer.start(spec, false), "${d.name}: fritéza sa po príkaze rozbehla – hneď som poslal Stop. Skontroluj ju!")
            }
            return null
        }
        val back = sent.filter { (k, v) -> v == false && after[k] == true }
        if (back.isEmpty()) return null
        return OffRescue(back, "${d.name}: po vypnutí ostalo zapnuté – poslal som vypnutie ešte raz.")
    }

    /**
     * Vypnutie cez Tuya cloud (odchod mimo domu). [values] = čerstvý stav z cloudu (shadow) podľa čísla DP, [fresh] =
     * podarilo sa ho načítať. Svetlá a zásuvky ako doteraz (vypínač, aj odhad kódu); rizikové zariadenia len podľa
     * [offStep] a len s kódom zo schémy.
     */
    fun cloudOff(d: Device, values: Map<String, Any>, fresh: Boolean): CloudOffPlan {
        if (!risky(d, values)) {
            val opts = AutoDevices.offCommands(d, values)
            if (fresh && opts.isNotEmpty()) {
                // vypínače, ktoré cloud hlási ako vypnuté, netreba posielať
                val dps = Scenes.offCommand(d, values)
                if (dps != null && dps.all { (k, _) -> values[k] == false }) return CloudOffPlan(emptyList(), null)
            }
            return if (opts.isEmpty()) CloudOffPlan(emptyList(), SKIP_NO_SWITCH) else CloudOffPlan(opts, null)
        }
        return when (val step = offStep(d, values, fresh)) {
            is OffStep.Idle -> CloudOffPlan(emptyList(), null)
            is OffStep.Skip -> CloudOffPlan(emptyList(), step.reason)
            is OffStep.Send -> {
                val codes = step.cmd.map { (dp, v) -> (d.schema[dp]?.code ?: return CloudOffPlan(emptyList(), if (looksLikeFryer(d, values)) SKIP_NO_STOP else SKIP_NO_SWITCH)) to v }
                CloudOffPlan(listOf(codes), null)
            }
        }
    }

    /** Stav z cloudu (shadow) ako hodnoty podľa čísla DP. */
    fun shadowValues(props: List<ShadowProp>): Map<String, Any> =
        props.mapNotNull { p -> p.value?.let { p.dp.toString() to it } }.toMap()

    /** „preskočené: fritéza BH-AF2 (stav neznámy)“ */
    fun skippedText(steps: List<OffStep>): String? {
        val s = steps.filterIsInstance<OffStep.Skip>()
        if (s.isEmpty()) return null
        return "preskočené: " + s.joinToString { "${it.device.name} (${it.reason})" }
    }
}
