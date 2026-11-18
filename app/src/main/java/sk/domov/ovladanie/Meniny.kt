package sk.domov.ovladanie

import java.time.LocalDate

/**
 * Slovenský meninový kalendár (Oficiálne kalendárium, Kalendárová komisia pri Ministerstve kultúry SR,
 * verzia 2025: 475 mien na 366 dní; zdroj https://www.culture.gov.sk/sk/oficialne-kalendarium).
 * Bez mena sú len 1. 1., 1. 5., 2. 11. a 25. 12. – vtedy sa ukáže názov sviatku. 29. 2. (Radomír) platí len v priestupnom roku.
 * Súbor nepoužíva Android, testuje sa v JVM (tools/MeninyTest.kt).
 */
object Meniny {
    /** Dni bez mena a ich sviatok. */
    private val BEZ_MENA = mapOf(
        (1 to 1) to "Nový rok, Deň vzniku SR",
        (5 to 1) to "Sviatok práce",
        (11 to 2) to "Pamiatka zosnulých",
        (12 to 25) to "1. sviatok vianočný",
    )

    private val DNI = listOf("Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa")
    private val MESIACE = listOf(
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    )

    /** Mená pre [mesiac] (1–12) a [den]; prázdne pole ("") = deň bez mena. */
    fun mena(mesiac: Int, den: Int): String = TABULKA[mesiac - 1][den - 1]

    /** Mená v tento deň ako zoznam (prázdny, ak je deň bez mena). */
    fun zoznam(datum: LocalDate): List<String> =
        mena(datum.monthValue, datum.dayOfMonth).split(", ").filter { it.isNotBlank() }

    /** Text pre deň: mená „Alžbeta, Liliana“, alebo názov sviatku, keď deň nemá meno. */
    fun text(datum: LocalDate): String {
        val m = mena(datum.monthValue, datum.dayOfMonth)
        return if (m.isBlank()) BEZ_MENA[datum.monthValue to datum.dayOfMonth] ?: "bez mena" else m
    }

    /** „Streda 18. novembra“ */
    fun datumText(datum: LocalDate): String =
        "${DNI[datum.dayOfWeek.value - 1]} ${datum.dayOfMonth}. ${MESIACE[datum.monthValue - 1]}"

    /** „18. novembra“ */
    fun datumKratko(datum: LocalDate): String = "${datum.dayOfMonth}. ${MESIACE[datum.monthValue - 1]}"

    /** Prvý riadok pásu: „Streda 18. novembra · Meniny: Eugen“ (v deň bez mena „… · Nový rok, Deň vzniku SR“). */
    fun dnes(datum: LocalDate): String {
        val bez = mena(datum.monthValue, datum.dayOfMonth).isBlank()
        return "${datumText(datum)} · " + if (bez) text(datum) else "Meniny: ${text(datum)}"
    }

    /** Druhý riadok pásu: „Zajtra 19. novembra · Alžbeta, Liliana“. */
    fun zajtra(datum: LocalDate): String {
        val z = datum.plusDays(1)
        return "Zajtra ${datumKratko(z)} · ${text(z)}"
    }

    // [mesiac - 1][deň - 1]
    private val TABULKA: Array<Array<String>> = arrayOf(
        // Január
        arrayOf(
            "",  // 1. 1.
            "Alexandra, Sandra, Karina",  // 2. 1.
            "Daniela",  // 3. 1.
            "Drahoslav",  // 4. 1.
            "Andrea, Artur",  // 5. 1.
            "Antónia",  // 6. 1.
            "Bohuslava, Atila",  // 7. 1.
            "Severín",  // 8. 1.
            "Alexej, Alex, Alexia",  // 9. 1.
            "Daša",  // 10. 1.
            "Malvína",  // 11. 1.
            "Ernest, Ernestína",  // 12. 1.
            "Rastislav",  // 13. 1.
            "Radovan, Radovana",  // 14. 1.
            "Dobroslav, Dobroslava",  // 15. 1.
            "Kristína",  // 16. 1.
            "Nataša",  // 17. 1.
            "Bohdana",  // 18. 1.
            "Mário, Sára, Zara",  // 19. 1.
            "Dalibor, Sebastián, Fabián",  // 20. 1.
            "Vincent",  // 21. 1.
            "Zora, Zoran, Auróra",  // 22. 1.
            "Miloš",  // 23. 1.
            "Timotej, Timon, Tímea",  // 24. 1.
            "Gejza",  // 25. 1.
            "Tamara",  // 26. 1.
            "Bohuš",  // 27. 1.
            "Alfonz",  // 28. 1.
            "Gašpar",  // 29. 1.
            "Ema",  // 30. 1.
            "Emil",  // 31. 1.
        ),
        // Február
        arrayOf(
            "Tatiana, Táňa",  // 1. 2.
            "Erich, Erik, Erika",  // 2. 2.
            "Blažej",  // 3. 2.
            "Veronika",  // 4. 2.
            "Agáta",  // 5. 2.
            "Dorota",  // 6. 2.
            "Vanda",  // 7. 2.
            "Zoja",  // 8. 2.
            "Zdeno, Zdenko, Ronald",  // 9. 2.
            "Gabriela",  // 10. 2.
            "Dezider",  // 11. 2.
            "Perla",  // 12. 2.
            "Arpád",  // 13. 2.
            "Valentín",  // 14. 2.
            "Pravoslav",  // 15. 2.
            "Ida, Liana",  // 16. 2.
            "Miloslava",  // 17. 2.
            "Jaromír, Jaromíra",  // 18. 2.
            "Vlasta",  // 19. 2.
            "Lívia, Aladár",  // 20. 2.
            "Eleonóra",  // 21. 2.
            "Etela",  // 22. 2.
            "Roman, Romana",  // 23. 2.
            "Matej, Matiáš, Matias",  // 24. 2.
            "Frederik, Frederika",  // 25. 2.
            "Viktor",  // 26. 2.
            "Alexander",  // 27. 2.
            "Zlatica",  // 28. 2.
            "Radomír",  // 29. 2.
        ),
        // Marec
        arrayOf(
            "Albín",  // 1. 3.
            "Anežka",  // 2. 3.
            "Bohumil, Bohumila",  // 3. 3.
            "Kazimír",  // 4. 3.
            "Fridrich",  // 5. 3.
            "Radoslav, Radoslava",  // 6. 3.
            "Tomáš",  // 7. 3.
            "Alan, Alana",  // 8. 3.
            "Františka",  // 9. 3.
            "Branislav, Bronislav, Bruno",  // 10. 3.
            "Angela, Angelika",  // 11. 3.
            "Gregor",  // 12. 3.
            "Vlastimil, Letícia",  // 13. 3.
            "Matilda",  // 14. 3.
            "Svetlana",  // 15. 3.
            "Boleslav",  // 16. 3.
            "Ľubica",  // 17. 3.
            "Eduard",  // 18. 3.
            "Jozef",  // 19. 3.
            "Víťazoslav",  // 20. 3.
            "Blahoslav",  // 21. 3.
            "Beňadik, Benedikt",  // 22. 3.
            "Adrián",  // 23. 3.
            "Gabriel",  // 24. 3.
            "Marián",  // 25. 3.
            "Emanuel",  // 26. 3.
            "Alena",  // 27. 3.
            "Soňa",  // 28. 3.
            "Miroslav",  // 29. 3.
            "Vieroslava",  // 30. 3.
            "Benjamín",  // 31. 3.
        ),
        // Apríl
        arrayOf(
            "Hugo",  // 1. 4.
            "Zita",  // 2. 4.
            "Richard",  // 3. 4.
            "Izidor",  // 4. 4.
            "Miroslava",  // 5. 4.
            "Irena",  // 6. 4.
            "Zoltán",  // 7. 4.
            "Albert",  // 8. 4.
            "Milena",  // 9. 4.
            "Igor",  // 10. 4.
            "Július",  // 11. 4.
            "Estera",  // 12. 4.
            "Aleš",  // 13. 4.
            "Justín, Justína",  // 14. 4.
            "Fedor",  // 15. 4.
            "Dana, Danica",  // 16. 4.
            "Rudolf",  // 17. 4.
            "Valér",  // 18. 4.
            "Jela",  // 19. 4.
            "Marcel",  // 20. 4.
            "Ervín",  // 21. 4.
            "Slavomír",  // 22. 4.
            "Vojtech",  // 23. 4.
            "Juraj",  // 24. 4.
            "Marek, Marko, Markus",  // 25. 4.
            "Jaroslava",  // 26. 4.
            "Jaroslav",  // 27. 4.
            "Jarmila",  // 28. 4.
            "Leo, Lea",  // 29. 4.
            "Anastázia",  // 30. 4.
        ),
        // Máj
        arrayOf(
            "",  // 1. 5.
            "Žigmund",  // 2. 5.
            "Galina",  // 3. 5.
            "Florián",  // 4. 5.
            "Lesana, Lesia",  // 5. 5.
            "Hermína",  // 6. 5.
            "Monika",  // 7. 5.
            "Ingrida",  // 8. 5.
            "Roland",  // 9. 5.
            "Viktória",  // 10. 5.
            "Blažena",  // 11. 5.
            "Pankrác",  // 12. 5.
            "Servác",  // 13. 5.
            "Bonifác",  // 14. 5.
            "Žofia, Sofia",  // 15. 5.
            "Svetozár",  // 16. 5.
            "Gizela",  // 17. 5.
            "Viola",  // 18. 5.
            "Gertrúda",  // 19. 5.
            "Bernard",  // 20. 5.
            "Zina",  // 21. 5.
            "Júlia, Juliána, Rita",  // 22. 5.
            "Želmíra",  // 23. 5.
            "Ela, Vanesa",  // 24. 5.
            "Urban",  // 25. 5.
            "Dušan",  // 26. 5.
            "Iveta",  // 27. 5.
            "Viliam",  // 28. 5.
            "Vilma",  // 29. 5.
            "Ferdinand",  // 30. 5.
            "Petrana, Petronela, Nela",  // 31. 5.
        ),
        // Jún
        arrayOf(
            "Žaneta",  // 1. 6.
            "Oxana, Xénia",  // 2. 6.
            "Karolína, Kevin",  // 3. 6.
            "Lenka",  // 4. 6.
            "Laura",  // 5. 6.
            "Norbert",  // 6. 6.
            "Róbert",  // 7. 6.
            "Medard",  // 8. 6.
            "Stanislava",  // 9. 6.
            "Margaréta, Gréta",  // 10. 6.
            "Jesika",  // 11. 6.
            "Zlatko",  // 12. 6.
            "Anton",  // 13. 6.
            "Vasil",  // 14. 6.
            "Vít",  // 15. 6.
            "Bianka, Blanka",  // 16. 6.
            "Adolf",  // 17. 6.
            "Vratislav",  // 18. 6.
            "Alfréd",  // 19. 6.
            "Valéria",  // 20. 6.
            "Alojz",  // 21. 6.
            "Paulína, Paula, Pavla",  // 22. 6.
            "Sidónia",  // 23. 6.
            "Ján",  // 24. 6.
            "Olívia, Tadeáš",  // 25. 6.
            "Adriána",  // 26. 6.
            "Ladislav, Ladislava",  // 27. 6.
            "Beáta",  // 28. 6.
            "Peter, Pavol, Petra",  // 29. 6.
            "Melánia",  // 30. 6.
        ),
        // Júl
        arrayOf(
            "Diana",  // 1. 7.
            "Berta",  // 2. 7.
            "Miloslav",  // 3. 7.
            "Prokop",  // 4. 7.
            "Cyril, Metod",  // 5. 7.
            "Patrik, Patrícia",  // 6. 7.
            "Oliver",  // 7. 7.
            "Ivan",  // 8. 7.
            "Lujza",  // 9. 7.
            "Amália",  // 10. 7.
            "Milota",  // 11. 7.
            "Nina",  // 12. 7.
            "Margita",  // 13. 7.
            "Kamil, Lara",  // 14. 7.
            "Henrich",  // 15. 7.
            "Drahomír, Drahomíra",  // 16. 7.
            "Bohuslav",  // 17. 7.
            "Kamila",  // 18. 7.
            "Šarlota, Dušana",  // 19. 7.
            "Eliáš, Iľja, Eliána",  // 20. 7.
            "Daniel",  // 21. 7.
            "Magdaléna",  // 22. 7.
            "Oľga",  // 23. 7.
            "Vladimír",  // 24. 7.
            "Jakub, Timur",  // 25. 7.
            "Anna, Hana, Aneta",  // 26. 7.
            "Božena",  // 27. 7.
            "Krištof",  // 28. 7.
            "Marta",  // 29. 7.
            "Libuša",  // 30. 7.
            "Ignác",  // 31. 7.
        ),
        // August
        arrayOf(
            "Božidara",  // 1. 8.
            "Gustáv",  // 2. 8.
            "Jerguš",  // 3. 8.
            "Dominik, Dominika",  // 4. 8.
            "Hortenzia",  // 5. 8.
            "Jozefína",  // 6. 8.
            "Štefánia",  // 7. 8.
            "Oskar",  // 8. 8.
            "Ľubomíra",  // 9. 8.
            "Vavrinec",  // 10. 8.
            "Zuzana",  // 11. 8.
            "Darina, Dárius, Dária",  // 12. 8.
            "Ľubomír",  // 13. 8.
            "Mojmír",  // 14. 8.
            "Marcela",  // 15. 8.
            "Leonard",  // 16. 8.
            "Milica",  // 17. 8.
            "Elena, Helena",  // 18. 8.
            "Lýdia",  // 19. 8.
            "Anabela, Bela",  // 20. 8.
            "Jana, Johana, Noema",  // 21. 8.
            "Tichomír",  // 22. 8.
            "Filip",  // 23. 8.
            "Bartolomej",  // 24. 8.
            "Ľudovít",  // 25. 8.
            "Samuel",  // 26. 8.
            "Silvia",  // 27. 8.
            "Augustín",  // 28. 8.
            "Nikola, Nikolaj, Nikoleta",  // 29. 8.
            "Ružena",  // 30. 8.
            "Nora",  // 31. 8.
        ),
        // September
        arrayOf(
            "Drahoslava",  // 1. 9.
            "Linda, Rebeka",  // 2. 9.
            "Belo",  // 3. 9.
            "Rozália",  // 4. 9.
            "Regína",  // 5. 9.
            "Alica",  // 6. 9.
            "Marianna",  // 7. 9.
            "Miriama",  // 8. 9.
            "Martina",  // 9. 9.
            "Oleg",  // 10. 9.
            "Bystrík",  // 11. 9.
            "Mária, Mia",  // 12. 9.
            "Ctibor, Tobiáš, Tobias",  // 13. 9.
            "Ľudomil, Radka",  // 14. 9.
            "Jolana",  // 15. 9.
            "Ľudmila",  // 16. 9.
            "Olympia, Melisa",  // 17. 9.
            "Eugénia",  // 18. 9.
            "Konštantín",  // 19. 9.
            "Ľuboslav, Ľuboslava",  // 20. 9.
            "Matúš",  // 21. 9.
            "Móric",  // 22. 9.
            "Zdenka",  // 23. 9.
            "Ľubor, Ľuboš",  // 24. 9.
            "Vladislav",  // 25. 9.
            "Edita",  // 26. 9.
            "Cyprián, Damián",  // 27. 9.
            "Václav",  // 28. 9.
            "Michal, Michael, Michaela",  // 29. 9.
            "Jarolím",  // 30. 9.
        ),
        // Október
        arrayOf(
            "Arnold",  // 1. 10.
            "Levoslav",  // 2. 10.
            "Stela",  // 3. 10.
            "František",  // 4. 10.
            "Viera",  // 5. 10.
            "Natália",  // 6. 10.
            "Eliška, Elisa",  // 7. 10.
            "Brigita",  // 8. 10.
            "Dionýz",  // 9. 10.
            "Slavomíra",  // 10. 10.
            "Valentína",  // 11. 10.
            "Maximilián, Maxim, Max",  // 12. 10.
            "Koloman",  // 13. 10.
            "Boris",  // 14. 10.
            "Terézia, Tereza",  // 15. 10.
            "Vladimíra",  // 16. 10.
            "Hedviga",  // 17. 10.
            "Lukáš",  // 18. 10.
            "Kristián",  // 19. 10.
            "Vendelín",  // 20. 10.
            "Uršuľa",  // 21. 10.
            "Sergej",  // 22. 10.
            "Alojzia",  // 23. 10.
            "Kvetoslava",  // 24. 10.
            "Aurel",  // 25. 10.
            "Demeter",  // 26. 10.
            "Sabína",  // 27. 10.
            "Dobromila",  // 28. 10.
            "Klára, Kiara",  // 29. 10.
            "Šimon, Simon, Simona",  // 30. 10.
            "Aurélia",  // 31. 10.
        ),
        // November
        arrayOf(
            "Denis, Denisa",  // 1. 11.
            "",  // 2. 11.
            "Hubert",  // 3. 11.
            "Karol",  // 4. 11.
            "Imrich",  // 5. 11.
            "Renáta",  // 6. 11.
            "René",  // 7. 11.
            "Bohumír",  // 8. 11.
            "Teodor, Teo",  // 9. 11.
            "Tibor",  // 10. 11.
            "Martin, Maroš",  // 11. 11.
            "Svätopluk, Jonáš",  // 12. 11.
            "Stanislav",  // 13. 11.
            "Irma",  // 14. 11.
            "Leopold",  // 15. 11.
            "Agnesa",  // 16. 11.
            "Klaudia",  // 17. 11.
            "Eugen",  // 18. 11.
            "Alžbeta, Liliana",  // 19. 11.
            "Félix",  // 20. 11.
            "Elvíra",  // 21. 11.
            "Cecília",  // 22. 11.
            "Klement",  // 23. 11.
            "Emília",  // 24. 11.
            "Katarína",  // 25. 11.
            "Kornel",  // 26. 11.
            "Milan",  // 27. 11.
            "Henrieta",  // 28. 11.
            "Vratko",  // 29. 11.
            "Andrej, Ondrej, Andreas",  // 30. 11.
        ),
        // December
        arrayOf(
            "Edmund",  // 1. 12.
            "Bibiána, Viviána",  // 2. 12.
            "Oldrich",  // 3. 12.
            "Barbora, Barbara",  // 4. 12.
            "Oto",  // 5. 12.
            "Mikuláš, Nikolas",  // 6. 12.
            "Ambróz",  // 7. 12.
            "Marína",  // 8. 12.
            "Izabela",  // 9. 12.
            "Radúz",  // 10. 12.
            "Hilda",  // 11. 12.
            "Otília",  // 12. 12.
            "Lucia",  // 13. 12.
            "Branislava, Bronislava",  // 14. 12.
            "Ivica",  // 15. 12.
            "Albína",  // 16. 12.
            "Kornélia",  // 17. 12.
            "Sláva, Slávka",  // 18. 12.
            "Judita",  // 19. 12.
            "Dagmara",  // 20. 12.
            "Bohdan, Noel",  // 21. 12.
            "Adela",  // 22. 12.
            "Nadežda, Naďa",  // 23. 12.
            "Adam, Eva",  // 24. 12.
            "",  // 25. 12.
            "Štefan",  // 26. 12.
            "Filoména",  // 27. 12.
            "Ivana, Ivona",  // 28. 12.
            "Milada, Nátan",  // 29. 12.
            "Dávid",  // 30. 12.
            "Silvester",  // 31. 12.
        ),
    )
}
