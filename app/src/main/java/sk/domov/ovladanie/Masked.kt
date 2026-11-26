package sk.domov.ovladanie

/** Tajná hodnota do toString()/logov: nikdy nie obsah, len či je nastavená a jej dĺžka. */
internal fun masked(secret: String?): String = when {
    secret == null -> "null"
    secret.isEmpty() -> "\"\""
    else -> "***(${secret.length})"
}

internal fun masked(secret: ByteArray?): String = if (secret == null) "null" else "***(${secret.size} B)"
