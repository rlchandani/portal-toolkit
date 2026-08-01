package com.portalinstaller.data.tools

/**
 * Clasifica o comanda shell ca fiind distructiva (schimba starea sau pornirea
 * dispozitivului) pentru a cere confirmare inainte de rulare. Regulile sunt
 * conservatoare: preferam un fals-pozitiv (o confirmare in plus) unei comenzi
 * distructive rulate din greseala. Nu incercam sa interpretam corect gramatica
 * shell — cautam doar cuvinte-cheie cunoscute ca periculoase.
 */
object ShellCommandClassifier {

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\b"""),
        Regex("""\breboot\b"""),
        Regex("""\bpm\s+(disable|uninstall|clear)\b"""),
        Regex("""\bsettings\s+put\b"""),
        Regex("""\bsvc\b"""),
        Regex("""\bkillall?\b""")
    )

    fun isDestructive(command: String): Boolean {
        val normalized = command.trim().lowercase()
        return DESTRUCTIVE.any { it.containsMatchIn(normalized) }
    }
}
