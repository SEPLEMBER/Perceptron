package es.zelliot.perceptron

import android.content.Context

object TesseractEngine2 {
    
    // Константа версии, чтобы менять её в одном месте
    private const val VERSION = "5.01 «QUASAR»"

    /**
     * Расширенный движок для системных команд и утилит.
     * Парсится вместе с Engine1, но не ломает его логику.
     */
    fun evaluateSystemCommand(context: Context, script: String): String {
        val trimmed = script.trim()
        
        return when {
            // Команда ABOUT
            trimmed.equals("ABOUT", ignoreCase = true) -> {
                buildString {
                    appendLine(context.getString(R.string.cmd_about_title))
                    appendLine(context.getString(R.string.cmd_about_desc, VERSION))
                    appendLine("")
                    appendLine(context.getString(R.string.cmd_about_dev))
                    appendLine("")
                    appendLine(context.getString(R.string.cmd_about_features))
                }
            }

            // Команда HELP
            trimmed.equals("HELP", ignoreCase = true) || trimmed.equals("?", ignoreCase = true) -> {
                context.getString(R.string.cmd_help_content)
            }

            // Команда VERSION
            trimmed.equals("VERSION", ignoreCase = true) || trimmed.equals("VER", ignoreCase = true) -> {
                context.getString(R.string.cmd_version, VERSION)
            }

            // Команда очистки экрана (CLS/CLEAR)
            trimmed.equals("CLS", ignoreCase = true) || trimmed.equals("CLEAR", ignoreCase = true) -> {
                "__TESSERACT_CLEAR__"
            }

            else -> "" // Если команда не распознана, возвращаем пустую строку
        }
    }
}
