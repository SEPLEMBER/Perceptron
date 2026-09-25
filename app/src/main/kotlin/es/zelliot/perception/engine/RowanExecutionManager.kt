package es.zelliot.perceptron.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

// ============================================================================
// КОНТЕКСТ ВЫПОЛНЕНИЯ
// ============================================================================
data class RowanContext(
    val memory: LongArray = LongArray(1_000_000),          // Сырая память для mem[]
    val variables: MutableMap<String, Any> = mutableMapOf(), // Для внешней инъекции переменных из Android
    val uiState: MutableMap<String, Any> = mutableMapOf(),   // Состояние для view {}
    val output: StringBuilder = StringBuilder(),             // Буфер для функции print()
    var isRunning: Boolean = true
)

// ============================================================================
// МЕНЕДЖЕР ВЫПОЛНЕНИЯ (Точка входа из RowanActivity)
// ============================================================================
object RowanExecutionManager {
    private val executionMutex = Mutex()

    suspend fun execute(context: Context, script: String): String = executionMutex.withLock {
        val rowanContext = RowanContext()
        
        try {
            // ЭТАП 1: Компиляция и валидация (RowanEngine 1)
            // Лексер разбивает текст на токены, Парсер строит AST
            val tokens = RowanEngine1.tokenize(script)
            val ast = RowanEngine1.parse(tokens)
            
            // ЭТАП 2: Высокоуровневая логика и выполнение (RowanEngine 2)
            // VM выполняет AST, управляя циклами, функциями и переменными
            RowanEngine2.execute(ast, rowanContext)
            
            // ЭТАП 3: Низкоуровневая финализация (RowanEngine 3)
            // Проверка целостности памяти, синхронизация UI, форматирование вывода
            val finalOutput = RowanEngine3.finalize(rowanContext)
            
            return@withLock finalOutput.ifEmpty { "void" }
            
        } catch (e: RowanExitException) {
            // Специальное исключение для команды exit <delay>
            val output = rowanContext.output.toString().trim()
            return@withLock "__ROWAN_EXIT__:${e.delayMs}\n$output"
            
        } catch (e: RowanMemoryBoundsException) {
            // Попытка выхода за границы mem[] (ловим отдельно для четкого сообщения)
            return@withLock "Memory Trap: ${e.message}"
            
        } catch (e: RowanError) {
            // Ошибки синтаксиса или выполнения, пойманные внутри VM/Lexer
            return@withLock "Error: ${e.message}"
            
        } catch (e: Exception) {
            // Неожиданные ошибки (например, нехватка памяти JVM или сбой Android)
            return@withLock "Fatal Runtime Error: ${e.message ?: "Unknown"}"
            
        } finally {
            // Гарантированная очистка после выполнения (Security & Memory Hygiene)
            rowanContext.isRunning = false
            rowanContext.variables.clear()
            rowanContext.uiState.clear()
            // Память (LongArray) не очищается полностью для производительности, 
            // но Engine 3 может затереть конкретные сектора при необходимости.
        }
    }
}

// ============================================================================
// ROWAN ENGINE 1: Frontend (Lexer + Parser)
// ============================================================================
object RowanEngine1 {
    /**
     * Превращает исходный код в список токенов.
     * Выбрасывает RowanError при неизвестных символах или нарушении формата.
     */
    fun tokenize(script: String): List<RowanToken> {
        val lexer = RowanLexer(script)
        return lexer.tokenize()
    }

    /**
     * Превращает список токенов в Абстрактное Синтаксическое Дерево (AST).
     * Выбрасывает RowanError при синтаксических ошибках.
     */
    fun parse(tokens: List<RowanToken>): List<RowanStmt> {
        val parser = RowanParser(tokens)
        return parser.parse()
    }
}

// ============================================================================
// ROWAN ENGINE 2: Backend (Virtual Machine)
// ============================================================================
object RowanEngine2 {
    /**
     * Выполняет AST в предоставленном контексте.
     * Инкапсулирует создание и запуск RowanVM.
     */
    fun execute(ast: List<RowanStmt>, ctx: RowanContext) {
        val vm = RowanVM(ctx)
        vm.execute(ast)
    }
}

// ============================================================================
// ROWAN ENGINE 3: Low-level Operations & Finalization
// ============================================================================
object RowanEngine3 {
    
    // -------------------------------------------------------------------------
    // Аппаратные операции с памятью (Эмулятор битов)
    // -------------------------------------------------------------------------
    
    fun setBit(memory: LongArray, address: Int, bitIndex: Int) {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        memory[address] = memory[address] or (1L shl bitIndex)
    }

    fun clearBit(memory: LongArray, address: Int, bitIndex: Int) {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        memory[address] = memory[address] and (1L shl bitIndex).inv()
    }

    fun getBit(memory: LongArray, address: Int, bitIndex: Int): Boolean {
        require(address in memory.indices) { "Memory address $address out of bounds" }
        require(bitIndex in 0..63) { "Bit index must be between 0 and 63" }
        return (memory[address] and (1L shl bitIndex)) != 0L
    }

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

    // -------------------------------------------------------------------------
    // Финализация и мост к Android UI
    // -------------------------------------------------------------------------
    
    /**
     * Финальная обработка контекста перед возвратом результата в Android.
     */
    fun finalize(ctx: RowanContext): String {
        // 1. Синхронизация UI (если скрипт использовал view { ... })
        if (ctx.uiState.isNotEmpty()) {
            Handler(Looper.getMainLooper()).post {
                // Здесь можно вызвать метод из RowanActivity, например:
                // RowanActivity.instance?.updateUi(ctx.uiState)
                android.util.Log.d("RowanEngine3", "UI State Updated: ${ctx.uiState}")
            }
        }

        // 2. Безопасная очистка чувствительных данных (если помечено)
        if (ctx.variables.containsValue("__SECURE_WIPE__")) {
            ctx.memory.fill(0L)
        }

        // 3. Форматирование вывода
        return ctx.output.toString().trim()
    }
}

// ============================================================================
// ИСКЛЮЧЕНИЯ (Для четкого разделения типов ошибок)
// ============================================================================

/** Ошибки, связанные с синтаксисом или семантикой языка */
class RowanError(message: String, val line: Int = 0) : Exception(if (line > 0) "Line $line: $message" else message)

/** Исключение для принудительного завершения скрипта (команда exit) */
class RowanExitException(val delayMs: Long) : Exception()

/** Ошибки безопасности памяти (выход за границы массива mem) */
class RowanMemoryBoundsException(message: String) : Exception(message)
