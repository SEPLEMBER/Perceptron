package es.zelliot.perceptron.engine

import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * ROWAN ENGINE 3: Low-Level Operations, Memory Management & UI Bridging
 * 
 * Отвечает за:
 * 1. Прямые побитовые операции с "железной" памятью (LongArray).
 * 2. Валидацию и передачу состояния UI в главный поток Android.
 * 3. Безопасную очистку и финализацию контекста после выполнения.
 */
object RowanEngine3 {

    // =========================================================================
    // 1. АППАРАТНЫЕ ОПЕРАЦИИ С ПАМЯТЬЮ (Эмулятор битов)
    // =========================================================================

    /**
     * Установить конкретный бит в ячейке памяти.
     * @param memory Глобальный массив памяти
     * @param address Индекс ячейки (Long)
     * @param bitIndex Номер бита (0-63)
     */
    fun setBit(memory: LongArray, address: Int, bitIndex: Int) {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        memory[address] = memory[address] or (1L shl bitIndex)
    }

    /**
     * Сбросить конкретный бит в ячейке памяти.
     */
    fun clearBit(memory: LongArray, address: Int, bitIndex: Int) {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        memory[address] = memory[address] and (1L shl bitIndex).inv()
    }

    /**
     * Прочитать значение конкретного бита.
     */
    fun getBit(memory: LongArray, address: Int, bitIndex: Int): Boolean {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        return (memory[address] and (1L shl bitIndex)) != 0L
    }

    /**
     * Прямая запись байта в память (для эмуляции строк или сырых данных).
     */
    fun writeByte(memory: LongArray, address: Int, byteValue: Byte) {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        // В реальной архитектуре здесь был бы сдвиг в зависимости от endianness,
        // но для простоты 1 элемент LongArray = 1 байт данных (или мы используем младшие 8 бит)
        memory[address] = byteValue.toLong() and 0xFFL
    }

    /**
     * Сгенерировать hex-дамп участка памяти (для отладки).
     */
    fun dumpMemory(memory: LongArray, startAddress: Int, length: Int): String {
        val safeLength = length.coerceAtMost(memory.size - startAddress)
        val sb = StringBuilder()
        sb.appendLine("--- MEMORY DUMP [0x${startAddress.toString(16)} - 0x${(startAddress + safeLength).toString(16)}] ---")
        
        for (i in 0 until safeLength step 8) {
            val addr = startAddress + i
            sb.append(String.format(Locale.US, "%04X: ", addr))
            for (j in 0 until 8) {
                if (addr + j < memory.size) {
                    sb.append(String.format(Locale.US, "%02X ", memory[addr + j].toByte()))
                } else {
                    sb.append("   ")
                }
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    // =========================================================================
    // 2. МОСТ К ANDROID UI (UI Bridging)
    // =========================================================================

    /**
     * Вызывается из RowanExecutionManager после выполнения скрипта.
     * Анализирует ctx.uiState и инициирует обновление интерфейса в главном потоке.
     */
    fun syncUiState(uiState: Map<String, Any>) {
        if (uiState.isEmpty()) return

        // Передаем управление в главный поток Android (UI Thread)
        Handler(Looper.getMainLooper()).post {
            try {
                // Здесь должна быть логика обновления реальных View.
                // Например, если у тебя есть синглтон или callback в Activity:
                // RowanActivity.instance?.updateUiFromEngine(uiState)
                
                // Для демонстрации просто логируем:
                android.util.Log.d("RowanEngine3", "UI State Updated: $uiState")
            } catch (e: Exception) {
                android.util.Log.e("RowanEngine3", "Failed to sync UI state", e)
            }
        }
    }

    // =========================================================================
    // 3. ФИНАЛИЗАЦИЯ И ОЧИСТКА (Finalization)
    // =========================================================================

    /**
     * Финальная обработка контекста перед возвратом результата.
     */
    fun finalize(ctx: RowanContext): String {
        // 1. Синхронизируем UI, если скрипт его менял
        syncUiState(ctx.uiState)

        // 2. Форматируем вывод
        val rawOutput = ctx.output.toString()
        val finalOutput = if (rawOutput.isBlank()) "void" else rawOutput.trim()

        // 3. Безопасная очистка (Security & Memory Hygiene)
        // Если скрипт работал с чувствительными данными (пароли, ключи),
        // мы можем затереть память нулями, чтобы она не осталась в куче JVM.
        if (ctx.variables.containsValue("__SECURE_WIPE__")) {
            ctx.memory.fill(0L)
        }

        // 4. Сброс флагов
        ctx.isRunning = false
        
        return finalOutput
    }
}
