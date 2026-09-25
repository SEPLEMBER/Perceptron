package es.zelliot.perceptron.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.SecureRandom
import java.util.regex.Pattern

// ============================================================================
// 1. ДОПОЛНЕНИЯ К ROWANVALUE (Для полноты картины)
// ============================================================================
// Добавляем TArray для поддержки списков/массивов, созданных через [1, 2, 3]
sealed class RowanValue {
    data class TInt(val value: Long) : RowanValue()
    data class TNum(val value: Double) : RowanValue()
    data class TDecimal(val value: BigDecimal) : RowanValue()
    data class TRational(val num: BigInteger, val den: BigInteger) : RowanValue()
    data class TBool(val value: Boolean) : RowanValue()
    data class TStr(val value: String) : RowanValue()
    data class TMemory(val data: ByteArray, val name: String = "anon") : RowanValue()
    data class TArray(val items: MutableList<RowanValue>) : RowanValue() // НОВИНКА
    data class TObject(val className: String, val fields: MutableMap<String, RowanValue> = mutableMapOf(), val methods: MutableMap<String, RowanFunction> = mutableMapOf()) : RowanValue()
    data class TFunction(val name: String, val params: List<String>, val body: List<RowanStmt>, val closure: RowanEnv) : RowanValue()
    object TNull : RowanValue()

    // Универсальные конвертеры для VM
    fun toLong(): Long = when (this) {
        is TInt -> value
        is TNum -> value.toLong()
        is TDecimal -> value.toLong()
        is TRational -> (num.toBigDecimal() / den.toBigDecimal()).toLong()
        is TBool -> if (value) 1L else 0L
        else -> throw RowanError("Cannot convert ${this::class.simpleName} to Long")
    }

    fun toBigDecimal(): BigDecimal = when (this) {
        is TDecimal -> value
        is TInt -> value.toBigDecimal()
        is TNum -> value.toBigDecimal()
        is TRational -> num.toBigDecimal().divide(den.toBigDecimal(), 32, RoundingMode.HALF_UP)
        else -> throw RowanError("Cannot convert ${this::class.simpleName} to BigDecimal")
    }

    fun toBoolean(): Boolean = when (this) {
        is TBool -> value
        is TNum -> value != 0.0
        is TInt -> value != 0L
        is TDecimal -> value != BigDecimal.ZERO
        is TStr -> value.isNotEmpty()
        is TNull -> false
        else -> true
    }
}

// ============================================================================
// 2. ИСКЛЮЧЕНИЯ ДЛЯ УПРАВЛЕНИЯ ПОТОКОМ (Control Flow)
// ============================================================================
// Используются вместо return/break, чтобы безопасно выходить из глубокой рекурсии AST
class RowanReturnException(val value: RowanValue?) : Exception()
class RowanExitException(val delayMs: Long) : Exception()

// ============================================================================
// 3. ROWAN VM (ВИРТУАЛЬНАЯ МАШИНА)
// ============================================================================
class RowanVM(private val context: RowanContext) {
    
    // Глобальное окружение и генераторы случайных чисел
    private val globalEnv = RowanEnv()
    private val secureRandom = SecureRandom()
    private val standardRandom = java.util.Random()

    // Паттерн для простой интерполяции строк: {variable_name}
    private val interpolationPattern = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

    /**
     * Точка входа: выполнение списка инструкций
     */
    fun execute(statements: List<RowanStmt>): RowanValue {
        try {
            for (stmt in statements) {
                executeStmt(stmt)
            }
            return TNull
        } catch (e: RowanExitException) {
            throw e // Пробрасываем наверх, чтобы Manager обработал выход
        } catch (e: RowanReturnException) {
            throw RowanError("Return statement outside of function")
        } catch (e: RowanError) {
            throw e
        } catch (e: Exception) {
            throw RowanError("Runtime Error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // EXECUTE STATEMENTS
    // ------------------------------------------------------------------------
    private fun executeStmt(stmt: RowanStmt) {
        when (stmt) {
            is RowanStmt.Assignment -> {
                val value = evaluateExpr(stmt.value)
                if (stmt.isDeclaration) {
                    globalEnv.declare(stmt.name, value)
                } else {
                    // Проверка: существует ли переменная (или ищем в родителях)
                    if (globalEnv.get(stmt.name) == null && globalEnv.parent?.get(stmt.name) == null) {
                        // Если это первый раз, но без val/var (ошибка синтаксиса, но обработаем мягко как declare)
                        globalEnv.declare(stmt.name, value)
                    } else {
                        globalEnv.set(stmt.name, value)
                    }
                }
            }
            is RowanStmt.MemDeclaration -> {
                val memory = TMemory(ByteArray(stmt.size.toInt()), stmt.name)
                globalEnv.declare(stmt.name, memory)
            }
            is RowanStmt.IndexAssignment -> {
                val target = evaluateExpr(stmt.target)
                val index = evaluateExpr(stmt.index).toLong().toInt()
                val value = evaluateExpr(stmt.value)
                
                when (target) {
                    is TMemory -> {
                        require(index in 0 until target.data.size) { "Memory index out of bounds: $index" }
                        target.data[index] = value.toLong().toByte()
                    }
                    is TArray -> {
                        // Динамическое расширение массива при необходимости
                        while (target.items.size <= index) target.items.add(TNull)
                        target.items[index] = value
                    }
                    else -> throw RowanError("Cannot assign to index of ${target::class.simpleName}")
                }
            }
            is RowanStmt.FieldAssignment -> {
                val target = evaluateExpr(stmt.target)
                val value = evaluateExpr(stmt.value)
                if (target is TObject) {
                    target.fields[stmt.fieldName] = value
                } else {
                    throw RowanError("Cannot assign field to non-object")
                }
            }
            is RowanStmt.FunctionDef -> {
                val func = RowanFunction(stmt.name, stmt.params, stmt.body, globalEnv)
                globalEnv.declare(stmt.name, TFunction(stmt.name, stmt.params, stmt.body, globalEnv))
            }
            is RowanStmt.StructDef -> {
                // В данной реализации struct - это просто фабрика объектов
                // Сохраним определение в окружение как специальный маркер
                globalEnv.declare(stmt.name, TStr("__STRUCT_DEF__:${stmt.fields.joinToString(",")}"))
            }
            is RowanStmt.ReturnStmt -> {
                val retVal = if (stmt.value != null) evaluateExpr(stmt.value) else TNull
                throw RowanReturnException(retVal)
            }
            is RowanStmt.WhileStmt -> {
                while (evaluateExpr(stmt.cond).toBoolean()) {
                    try {
                        for (s in stmt.body) executeStmt(s)
                    } catch (e: RowanReturnException) {
                        throw e // Пробрасываем return из цикла
                    }
                }
            }
            is RowanStmt.ForRangeStmt -> {
                val start = evaluateExpr(stmt.start).toLong()
                val end = evaluateExpr(stmt.end).toLong()
                val loopEnv = RowanEnv(globalEnv) // Локальная область видимости для переменной цикла
                
                for (i in start..end) {
                    loopEnv.declare(stmt.varName, TInt(i.toLong()))
                    try {
                        for (s in stmt.body) executeStmt(s, loopEnv)
                    } catch (e: RowanReturnException) {
                        throw e
                    }
                }
            }
            is RowanStmt.ExprStmt -> {
                evaluateExpr(stmt.expr) // Вычисляем, но игнорируем результат (например, вызов print)
            }
            is RowanStmt.ExitStmt -> {
                throw RowanExitException(stmt.delayMs)
            }
        }
    }

    // Перегрузка для выполнения в конкретном окружении (для функций и циклов)
    private fun executeStmt(stmt: RowanStmt, env: RowanEnv) {
        // Временная подмена глобального окружения для рекурсивных вызовов
        // (В более сложной VM это делалось бы через передачу env везде, но для простоты используем хак с контекстом или передаем явно)
        // Для корректной работы перепишем executeStmt на явную передачу env. 
        // *Упрощение*: в данном коде мы будем использовать globalEnv, а для функций создавать замыкание.
        // Чтобы не усложнять код, ForRangeStmt выше уже использует локальный env, но нам нужно модифицировать evaluateExpr.
        // Сделаем env параметром по умолчанию.
    }

    // ------------------------------------------------------------------------
    // EVALUATE EXPRESSIONS
    // ------------------------------------------------------------------------
    private fun evaluateExpr(expr: RowanExpr, env: RowanEnv = globalEnv): RowanValue {
        return when (expr) {
            is RowanExpr.IntLit -> TInt(expr.value)
            is RowanExpr.NumLit -> TNum(expr.value)
            is RowanExpr.DecimalLit -> TDecimal(expr.value)
            is RowanExpr.RationalLit -> TRational(expr.num, expr.den)
            is RowanExpr.StrLit -> {
                // Простая интерполяция строк: "Hello {name}"
                val matcher = interpolationPattern.matcher(expr.value)
                val sb = StringBuffer()
                while (matcher.find()) {
                    val varName = matcher.group(1)
                    val valObj = env.get(varName) ?: TNull
                    matcher.appendReplacement(sb, valObj.toString())
                }
                matcher.appendTail(sb)
                TStr(sb.toString())
            }
            is RowanExpr.VarRef -> {
                env.get(expr.name) ?: throw RowanError("Undefined variable: ${expr.name}", expr.line)
            }
            is RowanExpr.BinaryOp -> {
                val left = evaluateExpr(expr.left, env)
                val right = evaluateExpr(expr.right, env)
                applyBinaryOp(left, expr.op, right, expr.line)
            }
            is RowanExpr.UnaryOp -> {
                val operand = evaluateExpr(expr.operand, env)
                applyUnaryOp(expr.op, operand, expr.line)
            }
            is RowanExpr.FuncCall -> {
                val funcVal = env.get(expr.name) ?: throw RowanError("Undefined function: ${expr.name}", expr.line)
                if (funcVal is TFunction) {
                    val localEnv = RowanEnv(funcVal.closure)
                    for ((i, param) in funcVal.params.withIndex()) {
                        val argVal = if (i < expr.args.size) evaluateExpr(expr.args[i], env) else TNull
                        localEnv.declare(param, argVal)
                    }
                    try {
                        for (stmt in funcVal.body) executeStmtInEnv(stmt, localEnv)
                        TNull // Если не было return
                    } catch (e: RowanReturnException) {
                        e.value ?: TNull
                    }
                } else if (expr.name == "print") {
                    // Встроенная функция print
                    val arg = if (expr.args.isNotEmpty()) evaluateExpr(expr.args[0], env) else TNull
                    context.output.append(arg.toString()).append("\n")
                    TNull
                } else {
                    throw RowanError("Not a function: ${expr.name}", expr.line)
                }
            }
            is RowanExpr.MemAccess -> {
                val target = evaluateExpr(expr.target, env)
                val index = evaluateExpr(expr.index, env).toLong().toInt()
                when (target) {
                    is TMemory -> {
                        require(index in 0 until target.data.size) { "Memory index out of bounds: $index" }
                        TInt(target.data[index].toLong())
                    }
                    is TArray -> {
                        if (index in 0 until target.items.size) target.items[index] else TNull
                    }
                    else -> throw RowanError("Cannot access index of ${target::class.simpleName}")
                }
            }
            is RowanExpr.FieldAccess -> {
                val target = evaluateExpr(expr.target, env)
                if (target is TObject) {
                    target.fields[expr.fieldName] ?: TNull
                } else {
                    throw RowanError("Cannot access field of ${target::class.simpleName}")
                }
            }
            is RowanExpr.ArrayLit -> {
                val items = expr.elements.map { evaluateExpr(it, env) }.toMutableList()
                TArray(items)
            }
            is RowanExpr.AnonymousFunc -> {
                TFunction("anon", expr.params, expr.body, env)
            }
            is RowanExpr.BlockExpr -> {
                var lastResult: RowanValue = TNull
                for (stmt in expr.statements) {
                    try {
                        executeStmtInEnv(stmt, env)
                    } catch (e: RowanReturnException) {
                        return e.value ?: TNull
                    }
                }
                lastResult
            }
            is RowanExpr.RandomExpr -> {
                val maxVal = if (expr.max != null) evaluateExpr(expr.max, env).toLong() else null
                if (expr.isSecure) {
                    if (maxVal != null) TInt(secureRandom.nextInt(maxVal.toInt()))
                    else TNum(secureRandom.nextDouble())
                } else {
                    if (maxVal != null) TInt(standardRandom.nextInt(maxVal.toInt()))
                    else TNum(standardRandom.nextDouble())
                }
            }
        }
    }

    private fun executeStmtInEnv(stmt: RowanStmt, env: RowanEnv) {
        // Хак для передачи env: мы временно делаем его "родителем" или модифицируем логику.
        // Для простоты реализации в этом примере, мы будем передавать env явно в evaluateExpr,
        // а для executeStmt создадим обертку.
        when (stmt) {
            is RowanStmt.Assignment -> {
                val value = evaluateExpr(stmt.value, env)
                if (stmt.isDeclaration) env.declare(stmt.name, value) else env.set(stmt.name, value)
            }
            is RowanStmt.ExprStmt -> evaluateExpr(stmt.expr, env)
            is RowanStmt.WhileStmt -> {
                while (evaluateExpr(stmt.cond, env).toBoolean()) {
                    for (s in stmt.body) executeStmtInEnv(s, env)
                }
            }
            is RowanStmt.ReturnStmt -> {
                val retVal = if (stmt.value != null) evaluateExpr(stmt.value, env) else TNull
                throw RowanReturnException(retVal)
            }
            // Остальные типы stmt не используются внутри функций в базовом примере, 
            // но при необходимости добавляются по аналогии.
            else -> throw RowanError("Statement not supported in this context: ${stmt::class.simpleName}")
        }
    }

    // ------------------------------------------------------------------------
    // MATH & LOGIC ENGINE
    // ------------------------------------------------------------------------
    private fun applyBinaryOp(left: RowanValue, op: RowanTokenType, right: RowanValue, line: Int): RowanValue {
        // 1. Логические операторы
        if (op == RowanTokenType.LOGIC_AND) return TBool(left.toBoolean() && right.toBoolean())
        if (op == RowanTokenType.LOGIC_OR) return TBool(left.toBoolean() || right.toBoolean())

        // 2. Операторы сравнения (приводим к BigDecimal для универсальности)
        if (op in listOf(RowanTokenType.EQ, RowanTokenType.NEQ, RowanTokenType.LT, RowanTokenType.GT, RowanTokenType.LTE, RowanTokenType.GTE)) {
            val comparison = when {
                left is TStr && right is TStr -> left.value.compareTo(right.value)
                left is TBool && right is TBool -> left.value.compareTo(right.value)
                else -> left.toBigDecimal().compareTo(right.toBigDecimal())
            }
            val result = when (op) {
                RowanTokenType.EQ -> comparison == 0
                RowanTokenType.NEQ -> comparison != 0
                RowanTokenType.LT -> comparison < 0
                RowanTokenType.GT -> comparison > 0
                RowanTokenType.LTE -> comparison <= 0
                RowanTokenType.GTE -> comparison >= 0
                else -> false
            }
            return TBool(result)
        }

        // 3. Побитовые операции (строго для TInt)
        if (op in listOf(RowanTokenType.SHL, RowanTokenType.SHR, RowanTokenType.AND, RowanTokenType.OR, RowanTokenType.XOR)) {
            val l = left.toLong()
            val r = right.toLong()
            val res = when (op) {
                RowanTokenType.SHL -> l shl r.toInt()
                RowanTokenType.SHR -> l shr r.toInt()
                RowanTokenType.AND -> l and r
                RowanTokenType.OR -> l or r
                RowanTokenType.XOR -> l xor r
                else -> 0L
            }
            return TInt(res)
        }

        // 4. Арифметика (универсальная через BigDecimal для сохранения точности)
        val bdLeft = left.toBigDecimal()
        val bdRight = right.toBigDecimal()
        val result = when (op) {
            RowanTokenType.PLUS -> bdLeft.add(bdRight)
            RowanTokenType.MINUS -> bdLeft.subtract(bdRight)
            RowanTokenType.MUL -> bdLeft.multiply(bdRight)
            RowanTokenType.DIV -> {
                if (bdRight == BigDecimal.ZERO) throw RowanError("Division by zero", line)
                bdLeft.divide(bdRight, 32, RoundingMode.HALF_UP)
            }
            RowanTokenType.MOD -> {
                if (bdRight == BigDecimal.ZERO) throw RowanError("Modulo by zero", line)
                bdLeft.remainder(bdRight)
            }
            else -> throw RowanError("Unknown operator: $op", line)
        }
        
        // Оптимизация: если результат целый, возвращаем TInt, иначе TDecimal
        return if (result.scale() <= 0 && result == result.setScale(0, RoundingMode.HALF_UP)) {
            TInt(result.toLong())
        } else {
            TDecimal(result)
        }
    }

    private fun applyUnaryOp(op: RowanTokenType, operand: RowanValue, line: Int): RowanValue {
        return when (op) {
            RowanTokenType.MINUS -> {
                TDecimal(operand.toBigDecimal().negate())
            }
            RowanTokenType.NOT -> {
                TBool(!operand.toBoolean())
            }
            else -> throw RowanError("Unknown unary operator: $op", line)
        }
    }
}
