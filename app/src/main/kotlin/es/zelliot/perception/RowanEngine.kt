package es.zelliot.perceptron

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.random.Random

object RowanEngine {
    // Точка расширения для хост-приложения (безопасна, так как только читается)
    var syscallHandler: ((Long, List<RowanValue>) -> RowanValue)? = null

    fun evaluate(script: String): String {
        // ИСПРАВЛЕНИЕ 1: Полная изоляция состояния. Каждый вызов evaluate получает свои собственные карты.
        // Это делает движок на 100% потокобезопасным (thread-safe).
        val memory = mutableMapOf<Long, RowanValue>()
        val byteMemory = mutableMapOf<Long, Byte>()
        val outputBuffer = StringBuilder()
        
        val chunks = script.split("---").map { it.trim() }.filter { it.isNotEmpty() }
        val results = mutableListOf<String>()

        // Вложенная функция eval имеет доступ к локальным переменным memory, byteMemory, outputBuffer
        fun eval(ast: Any?, env: Environment): RowanValue {
            return when (ast) {
                is List<*> -> {
                    if (ast.isEmpty()) RowanValue.RNull
                    else {
                        val first = ast[0] as? String ?: return RowanValue.RString("Error: Invalid list head")
                        when (first) {
                            "do" -> { 
                                var res: RowanValue = RowanValue.RNull
                                for (i in 1 until ast.size) res = eval(ast[i], env)
                                res 
                            }
                            "set" -> { val v = eval(ast[2], env); env.set(ast[1] as String, v); v }
                            
                            // ИСПРАВЛЕНИЕ 2: Корректная лексическая область видимости для let
                            "let" -> { 
                                if (ast.size >= 4) {
                                    val name = ast[1] as String
                                    val value = eval(ast[2], env)
                                    val newEnv = env.extend()
                                    newEnv.set(name, value)
                                    var res: RowanValue = RowanValue.RNull
                                    for (i in 3 until ast.size) res = eval(ast[i], newEnv)
                                    res
                                } else {
                                    // Fallback: если тела нет, работает как set
                                    val name = ast[1] as String
                                    val value = eval(ast[2], env)
                                    env.set(name, value)
                                    value
                                }
                            }
                            
                            "if" -> { val cond = eval(ast[1], env); if (isTruthy(cond)) eval(ast[2], env) else if (ast.size > 3) eval(ast[3], env) else RowanValue.RNull }
                            "while" -> { 
                                var res: RowanValue = RowanValue.RNull
                                while (isTruthy(eval(ast[1], env))) { 
                                    try { res = eval(ast[2], env) } 
                                    catch (e: ControlFlow) { if (e.type == "break") break; if (e.type == "continue") continue; else throw e } 
                                }
                                res 
                            }
                            "return" -> throw ControlFlow("return", eval(ast[1], env))
                            "halt" -> throw ControlFlow("halt", eval(ast[1], env))
                            "yield" -> throw ControlFlow("yield", eval(ast[1], env))
                            
                            "fn" -> {
                                if (ast.size >= 4 && ast[1] is String && ast[2] is List<*>) {
                                    val name = ast[1] as String
                                    val params = (ast[2] as List<*>).mapNotNull { it as? String }
                                    val body = ast.subList(3, ast.size)
                                    val newEnv = env.extend()
                                    val func = RowanValue.RFunction(name, params, body, newEnv)
                                    newEnv.set(name, func)
                                    func
                                } else {
                                    val params = (ast[1] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                                    val body = ast.subList(2, ast.size)
                                    RowanValue.RFunction(null, params, body, env.extend())
                                }
                            }
                            "class" -> {
                                val name = ast[1] as? String ?: "Unknown"
                                val methods = mutableMapOf<String, MethodDef>()
                                for (i in 2 until ast.size) {
                                    val methodDef = ast[i] as? List<*> ?: continue
                                    val mName = methodDef[0] as? String ?: continue
                                    val params = (methodDef.getOrNull(1) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                                    val body = methodDef.subList(2, methodDef.size)
                                    methods[mName] = MethodDef(params, body)
                                }
                                env.set(name, RowanValue.RObject(name, mutableMapOf(), methods))
                                RowanValue.RString("Class '$name' defined")
                            }
                            "new" -> {
                                val cls = env.get(ast[1] as String) as? RowanValue.RObject ?: throw Exception("Not a class: ${ast[1]}")
                                val instance = RowanValue.RObject(cls.className, mutableMapOf(), cls.methods)
                                val initMethod = cls.methods["init"]
                                if (initMethod != null) {
                                    val newArgs = ast.subList(2, ast.size).mapNotNull { eval(it, env) }
                                    val initEnv = Environment().apply { 
                                        set("self", instance)
                                        initMethod.params.forEachIndexed { idx, p -> set(p, newArgs.getOrElse(idx) { RowanValue.RNull }) }
                                    }
                                    for (expr in initMethod.body) { eval(expr, initEnv) }
                                }
                                instance
                            }
                            "call" -> {
                                val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object")
                                val methodName = ast[2] as? String ?: throw Exception("Method name must be a symbol")
                                val methodDef = obj.methods[methodName] ?: throw Exception("Method '$methodName' not found")
                                val callArgs = ast.subList(3, ast.size).mapNotNull { eval(it, env) }
                                val callEnv = Environment().apply { 
                                    set("self", obj)
                                    methodDef.params.forEachIndexed { idx, p -> set(p, callArgs.getOrElse(idx) { RowanValue.RNull }) }
                                }
                                var res: RowanValue = RowanValue.RNull
                                for (expr in methodDef.body) { res = eval(expr, callEnv) }
                                res
                            }
                            "get" -> { val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object"); obj.fields[ast[2] as String] ?: RowanValue.RNull }
                            "put" -> { val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object"); obj.fields[ast[2] as String] = eval(ast[3], env); RowanValue.RNull }
                            "break" -> throw ControlFlow("break")
                            "continue" -> throw ControlFlow("continue")
                            
                            // ИСПРАВЛЕНИЕ 3: Надежное сравнение через структурное равенство объектов, а не строк
                            "match" -> {
                                val target = eval(ast[1], env)
                                var res: RowanValue = RowanValue.RNull
                                var i = 2
                                while (i < ast.size) {
                                    val pattern = ast[i]
                                    val body = ast.getOrNull(i + 1)
                                    val isDefault = (pattern is String && (pattern == "else" || pattern == "default"))
                                    val matches = isDefault || (eval(pattern, env) == target)
                                    
                                    if (matches) {
                                        res = eval(body, env)
                                        break
                                    }
                                    i += 2
                                }
                                res
                            }
                            
                            else -> { 
                                val func = if (env.has(first)) env.get(first) else getBuiltin(first, memory, byteMemory, outputBuffer)
                                val args = ast.subList(1, ast.size).mapNotNull { eval(it, env) }
                                when (func) {
                                    is RowanValue.RFunction -> {
                                        val callEnv = func.closure.extend()
                                        func.params.forEachIndexed { i, p -> callEnv.set(p, args.getOrElse(i) { RowanValue.RNull }) }
                                        try {
                                            var res: RowanValue = RowanValue.RNull
                                            for (expr in func.body) { res = eval(expr, callEnv) }
                                            res
                                        } catch (e: ControlFlow) {
                                            if (e.type == "return") e.value ?: RowanValue.RNull else throw e
                                        }
                                    }
                                    is RowanValue.RBuiltin -> func.fn(args)
                                    else -> RowanValue.RString("Error: '$first' is not callable")
                                }
                            }
                        }
                    }
                }
                is String -> env.get(ast)
                is RowanValue -> ast
                else -> RowanValue.RNull
            }
        }

        for ((index, chunk) in chunks.withIndex()) {
            if (chunks.size > 1) results.add("▶ ЗАПУСК БЛОКА ${index + 1} из ${chunks.size}")
            try {
                val tokens = tokenize(chunk)
                if (tokens.isEmpty()) continue
                val ast = parse(tokens)
                val env = Environment()
                registerBuiltins(env, memory, byteMemory, outputBuffer)
                
                var lastResult: RowanValue = RowanValue.RNull
                for (expr in ast) { 
                    lastResult = eval(expr, env) 
                }
                
                val bufferStr = outputBuffer.toString().trim()
                if (bufferStr.isNotEmpty()) {
                    results.add(bufferStr)
                    outputBuffer.clear()
                } else {
                    val resStr = lastResult.toString()
                    if (resStr != "null" && resStr.isNotEmpty() && !resStr.startsWith("Class ")) {
                        results.add(resStr)
                    }
                }
            } catch (e: ControlFlow) {
                when (e.type) {
                    "halt" -> results.add("⏹ HALT: ${e.value}")
                    "yield" -> results.add("⏸ YIELD: ${e.value}")
                    "return" -> results.add("↩ RETURN: ${e.value}")
                }
            } catch (t: Throwable) {
                val trace = t.stackTrace.take(3).joinToString("\n  → ") { "${it.fileName ?: "Unknown"}:${it.lineNumber}" }
                results.add("⚠️ КРИТИЧЕСКАЯ ОШИБКА В БЛОКЕ ${index + 1}: ${t.javaClass.simpleName}\n  Сообщение: ${t.message}\n  Стек:\n  $trace")
            }
        }
        return if (results.isEmpty()) "Выполнено успешно (нет вывода)" else results.joinToString("\n\n")
    }

    // --- ОСТАЛЬНАЯ ЧАСТЬ ДВИЖКА (Типы, Парсер, Builtins) ---
    // Встроенные функции теперь принимают контекст (memory, byteMemory, outputBuffer) как аргументы,
    // что делает их чистыми и безопасными.

    data class MethodDef(val params: List<String>, val body: List<Any?>)

    sealed class RowanValue {
        object RNull : RowanValue() { override fun toString() = "null" }
        data class RNum(val v: Double) : RowanValue() { 
            override fun toString() = if (v == v.toLong().toDouble() && !v.isInfinite() && !v.isNaN()) v.toLong().toString() else v.toString() 
        }
        data class RRat(val num: Long, val den: Long) : RowanValue() {
            init { require(den != 0L) { "Division by zero" } }
            fun simplify(): RRat {
                if (num == 0L) return RRat(0, 1)
                val g = gcd(abs(num), abs(den))
                val sign = if (den < 0) -1L else 1L
                return RRat((num / g) * sign, abs(den) / g)
            }
            override fun toString() = if (den == 1L) num.toString() else "$num/$den"
            fun toDouble() = num.toDouble() / den.toDouble()
            private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        }
        data class RString(val v: String) : RowanValue() { override fun toString() = "\"$v\"" }
        data class RList(val v: MutableList<RowanValue>) : RowanValue() { override fun toString() = "(${v.joinToString(" ")})" }
        data class RBytes(val v: ByteArray) : RowanValue() { 
            override fun toString() = "<Bytes:${v.size}>" 
            override fun equals(other: Any?) = other is RBytes && v.contentEquals(other.v)
            override fun hashCode() = v.contentHashCode()
        }
        data class RObject(val className: String, val fields: MutableMap<String, RowanValue>, val methods: Map<String, MethodDef>) : RowanValue() {
            override fun toString() = "<Object:$className>"
        }
        data class RFunction(val name: String?, val params: List<String>, val body: List<Any?>, val closure: Environment) : RowanValue() {
            override fun toString() = "<fn${if (name != null) " $name" else ""}>"
        }
        data class RBuiltin(val name: String, val fn: (List<RowanValue>) -> RowanValue) : RowanValue() {
            override fun toString() = "<builtin:$name>"
        }
    }

    class Environment(val parent: Environment? = null) {
        private val vars = mutableMapOf<String, RowanValue>()
        fun set(name: String, value: RowanValue) { vars[name] = value }
        fun get(name: String): RowanValue = vars[name] ?: parent?.get(name) ?: throw Exception("Undefined variable: '$name'")
        fun has(name: String): Boolean = vars.containsKey(name) || (parent?.has(name) ?: false)
        fun extend(): Environment = Environment(this)
    }

    class ControlFlow(val type: String, val value: RowanValue? = null) : Exception()

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                ' ', '\t', '\n', '\r', ',' -> i++ 
                '#', ';' -> { while (i < input.length && input[i] != '\n') i++ }
                '(', ')' -> { tokens.add(c.toString()); i++ }
                '"' -> {
                    var j = i + 1; val sb = StringBuilder()
                    while (j < input.length && input[j] != '"') {
                        if (input[j] == '\\' && j + 1 < input.length) { 
                            when (input[j+1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                '\\' -> sb.append('\\')
                                '"' -> sb.append('"')
                                else -> sb.append(input[j+1])
                            }
                            j += 2 
                        } else { 
                            sb.append(input[j]); j++ 
                        }
                    }
                    tokens.add("\"${sb}\""); i = j + 1
                }
                else -> {
                    var j = i
                    while (j < input.length && !" \t\n\r()\"#;,".contains(input[j])) j++
                    val tok = input.substring(i, j)
                    if (tok.isNotEmpty()) tokens.add(tok)
                    i = j
                }
            }
        }
        return tokens
    }

    private fun parse(tokens: List<String>): List<Any?> {
        var pos = 0
        fun read(): Any? {
            if (pos >= tokens.size) throw Exception("Unexpected EOF")
            val token = tokens[pos++]
            return when (token) {
                "(" -> {
                    val list = mutableListOf<Any?>()
                    while (pos < tokens.size && tokens[pos] != ")") list.add(read())
                    if (pos < tokens.size && tokens[pos] == ")") pos++
                    list
                }
                ")" -> throw Exception("Unexpected ')'")
                else -> parseAtom(token)
            }
        }
        val ast = mutableListOf<Any?>()
        while (pos < tokens.size) ast.add(read())
        return ast
    }

    private fun parseAtom(token: String): Any? {
        if (token.isEmpty()) return RowanValue.RNull
        if (token.startsWith("\"") && token.endsWith("\"")) return RowanValue.RString(token.substring(1, token.length - 1))
        if (token.contains("/") && token.count { it == '/' } == 1) {
            val parts = token.split("/")
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                val p0 = parts[0].replace("-", "")
                val p1 = parts[1]
                if (p0.all { it.isDigit() } && p1.all { it.isDigit() }) {
                    return RowanValue.RRat(parts[0].toLong(), parts[1].toLong()).simplify()
                }
            }
        }
        if (token.matches(Regex("-?\\d+(\\.\\d+)?"))) {
            return RowanValue.RNum(token.toDouble())
        }
        return token
    }

    private fun isTruthy(v: RowanValue): Boolean = v !is RowanValue.RNull && v != RowanValue.RNum(0.0) && v != RowanValue.RString("false")

    private fun getBuiltin(name: String, memory: MutableMap<Long, RowanValue>, byteMemory: MutableMap<Long, Byte>, outputBuffer: StringBuilder): RowanValue {
        return builtins[name]?.let { RowanValue.RBuiltin(name, { args -> it(args, memory, byteMemory, outputBuffer) }) } 
               ?: RowanValue.RString("Error: Unknown builtin '$name'")
    }

    private fun registerBuiltins(env: Environment, memory: MutableMap<Long, RowanValue>, byteMemory: MutableMap<Long, Byte>, outputBuffer: StringBuilder) { 
        builtins.forEach { (name, fn) -> 
            env.set(name, RowanValue.RBuiltin(name, { args -> fn(args, memory, byteMemory, outputBuffer) })) 
        } 
    }

    private fun mathOp(args: List<RowanValue>, doubleOp: (Double, Double) -> Double, ratOp: (Long, Long, Long, Long) -> RowanValue.RRat): RowanValue {
        if (args.all { it is RowanValue.RNum }) {
            return RowanValue.RNum(args.drop(1).fold((args[0] as RowanValue.RNum).v) { acc, v -> doubleOp(acc, (v as RowanValue.RNum).v) })
        }
        if (args.all { it is RowanValue.RRat }) {
            return args.drop(1).fold(args[0] as RowanValue.RRat) { acc: RowanValue.RRat, v -> 
                val r = v as RowanValue.RRat
                ratOp(acc.num, acc.den, r.num, r.den).simplify()
            }
        }
        return RowanValue.RNum(args.drop(1).fold((args[0] as? RowanValue.RNum)?.v ?: 0.0) { acc, v -> 
            doubleOp(acc, when(v) { is RowanValue.RNum -> v.v; is RowanValue.RRat -> v.toDouble(); else -> 0.0 }) 
        })
    }

    private fun getNum(v: RowanValue?): Double = when(v) { is RowanValue.RNum -> v.v; is RowanValue.RRat -> v.toDouble(); else -> 0.0 }
    private fun getLong(v: RowanValue?): Long = getNum(v).toLong()

    // Встроенные функции теперь принимают контекст как параметры
    private val builtins: Map<String, (List<RowanValue>, MutableMap<Long, RowanValue>, MutableMap<Long, Byte>, StringBuilder) -> RowanValue> = mapOf(
        "+" to { args, _, _, _ -> mathOp(args, { a, b -> a + b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 + n2 * d1, d1 * d2) }) },
        "-" to { args, _, _, _ -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(-(args[0] as RowanValue.RNum).v)
            else mathOp(args, { a, b -> a - b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 - n2 * d1, d1 * d2) }) 
        },
        "*" to { args, _, _, _ -> mathOp(args, { a, b -> a * b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * n2, d1 * d2) }) },
        "/" to { args, _, _, _ -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(1.0 / (args[0] as RowanValue.RNum).v) 
            else if (args.size == 1 && args[0] is RowanValue.RRat) (args[0] as RowanValue.RRat).let { r -> RowanValue.RRat(r.den, r.num).simplify() }
            else mathOp(args.drop(1), { a, b -> a / b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2, d1 * n2) })
        },
        "rat" to { args, _, _, _ -> RowanValue.RRat(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).simplify() },
        "float" to { args, _, _, _ -> RowanValue.RNum(getNum(args.getOrNull(0))) },
        
        "&" to { args, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) and getLong(args.getOrNull(1))).toDouble()) },
        "|" to { args, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) or getLong(args.getOrNull(1))).toDouble()) },
        "^" to { args, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) xor getLong(args.getOrNull(1))).toDouble()) },
        "~" to { args, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).inv().toDouble()) },
        "<<" to { args, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) shl getLong(args.getOrNull(1)).toInt()).toDouble()) },
        ">>" to { args, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) shr getLong(args.getOrNull(1)).toInt()).toDouble()) },
        "rol" to { args, _, _, _ -> 
            val v = getLong(args.getOrNull(0))
            val shift = (getLong(args.getOrNull(1)) and 63L).toInt()
            val invShift = (64 - shift) and 63 // ИСПРАВЛЕНИЕ: корректный сдвиг при shift=0
            RowanValue.RNum(((v shl shift) or (v ushr invShift)).toDouble())
        },
        "ror" to { args, _, _, _ -> 
            val v = getLong(args.getOrNull(0))
            val shift = (getLong(args.getOrNull(1)) and 63L).toInt()
            val invShift = (64 - shift) and 63
            RowanValue.RNum(((v ushr shift) or (v shl invShift)).toDouble())
        },
        "count-bits" to { args, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countOneBits().toDouble()) },
        "clz" to { args, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countLeadingZeroBits().toDouble()) },
        "ctz" to { args, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countTrailingZeroBits().toDouble()) },

        "==" to { args, _, _, _ -> if (args.getOrNull(0) == args.getOrNull(1)) RowanValue.RNum(1.0) else RowanValue.RNull },
        "!=" to { args, _, _, _ -> if (args.getOrNull(0) != args.getOrNull(1)) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<" to { args, _, _, _ -> if (getNum(args.getOrNull(0)) < getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">" to { args, _, _, _ -> if (getNum(args.getOrNull(0)) > getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<=" to { args, _, _, _ -> if (getNum(args.getOrNull(0)) <= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">=" to { args, _, _, _ -> if (getNum(args.getOrNull(0)) >= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "and" to { args, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull) && isTruthy(args.getOrNull(1) ?: RowanValue.RNull)) (args.getOrNull(1) ?: RowanValue.RNull) else RowanValue.RNull },
        "or" to { args, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) (args.getOrNull(0) ?: RowanValue.RNull) else (args.getOrNull(1) ?: RowanValue.RNull) },
        "not" to { args, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) RowanValue.RNull else RowanValue.RNum(1.0) },

        "store" to { args, memory, _, _ -> memory[getLong(args.getOrNull(0))] = args.getOrNull(1) ?: RowanValue.RNull; args.getOrNull(1) ?: RowanValue.RNull },
        "load" to { args, memory, _, _ -> memory[getLong(args.getOrNull(0))] ?: RowanValue.RNum(0.0) },
        "alloc" to { args, memory, _, _ -> 
            val addr = (memory.keys.maxOrNull() ?: -1L) + 1L
            val count = getLong(args.getOrNull(0)).toInt().coerceAtLeast(0)
            for(i in 0 until count) memory[addr+i] = RowanValue.RNull
            RowanValue.RNum(addr.toDouble()) 
        },
        "free" to { args, memory, _, _ -> 
            val count = getLong(args.getOrNull(0)).toInt().coerceAtLeast(0)
            val start = getLong(args.getOrNull(1))
            for(i in 0 until count) memory.remove(start + i)
            RowanValue.RNull 
        },
        "ptr" to { _, memory, _, _ -> RowanValue.RNum(((memory.keys.maxOrNull() ?: -1L) + 1L).toDouble()) },

        "byte" to { args, _, _, _ -> RowanValue.RBytes(byteArrayOf(getLong(args.getOrNull(0)).toByte())) },
        "bytes" to { args, _, _, _ -> 
            val size = getLong(args.getOrNull(0)).toInt().coerceAtLeast(0)
            val fill = if (args.size > 1) getLong(args.getOrNull(1)).toByte() else 0.toByte()
            RowanValue.RBytes(ByteArray(size) { fill })
        },
        "load8" to { args, _, byteMemory, _ -> 
            val addr = getLong(args.getOrNull(0))
            RowanValue.RNum(byteMemory[addr]?.toUByte()?.toInt()?.toDouble() ?: 0.0)
        },
        "store8" to { args, _, byteMemory, _ -> 
            val addr = getLong(args.getOrNull(0))
            val value = getLong(args.getOrNull(1)).toByte()
            byteMemory[addr] = value
            RowanValue.RNum(value.toUByte().toInt().toDouble())
        },
        "memset" to { args, _, byteMemory, _ ->
            val addr = getLong(args.getOrNull(0))
            val value = getLong(args.getOrNull(1)).toByte()
            // ИСПРАВЛЕНИЕ 4: Защита от ANR (зависания UI) при огромных значениях count
            val count = getLong(args.getOrNull(2)).toInt().coerceIn(0, 10_000_000)
            for (i in 0 until count) byteMemory[addr + i] = value
            RowanValue.RNull
        },
        "memcpy" to { args, _, byteMemory, _ ->
            val dst = getLong(args.getOrNull(0))
            val src = getLong(args.getOrNull(1))
            val count = getLong(args.getOrNull(2)).toInt().coerceIn(0, 10_000_000)
            for (i in 0 until count) byteMemory[dst + i] = byteMemory[src + i] ?: 0.toByte()
            RowanValue.RNull
        },

        "random" to { args, _, _, _ -> 
            if (args.size >= 2) RowanValue.RNum(Random.nextDouble(getNum(args[0]), getNum(args[1])))
            else RowanValue.RNum(Random.nextDouble())
        },
        "secrandom" to { _, _, _, _ -> RowanValue.RNum(SecureRandom().nextDouble()) },
        "randint" to { args, _, _, _ -> RowanValue.RNum(Random.nextLong(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).toDouble()) },
        "list" to { args, _, _, _ -> RowanValue.RList(args.toMutableList()) },
        "nth" to { args, _, _, _ -> (args.getOrNull(0) as? RowanValue.RList)?.v?.getOrNull(getLong(args.getOrNull(1)).toInt()) ?: RowanValue.RNull },
        "append" to { args, _, _, _ -> (args.getOrNull(0) as? RowanValue.RList)?.v?.add(args.getOrNull(1) ?: RowanValue.RNull); args.getOrNull(0) ?: RowanValue.RNull },
        "len" to { args, _, _, _ -> RowanValue.RNum((args.getOrNull(0) as? RowanValue.RList)?.v?.size?.toDouble() ?: 0.0) },
        "print" to { args, _, _, outputBuffer -> 
            val text = args.joinToString(" ") { it.toString().trim('"') }
            outputBuffer.append(text).append("\n")
            RowanValue.RString(text) 
        },
        "type" to { args, _, _, _ -> 
            RowanValue.RString(when(args.getOrNull(0)) { 
                is RowanValue.RNum -> "number"; is RowanValue.RRat -> "rational"; is RowanValue.RString -> "string"
                is RowanValue.RList -> "list"; is RowanValue.RObject -> "object"; is RowanValue.RFunction -> "function"
                is RowanValue.RBytes -> "bytes"; is RowanValue.RBuiltin -> "builtin"; else -> "null" 
            }) 
        },
        "cast" to { args, _, _, _ ->
            val target = args.getOrNull(0) ?: RowanValue.RNull
            val typeName = (args.getOrNull(1) as? RowanValue.RString)?.v ?: "string"
            when (typeName) {
                "number" -> when (target) {
                    is RowanValue.RString -> RowanValue.RNum(target.v.toDoubleOrNull() ?: 0.0)
                    is RowanValue.RRat -> RowanValue.RNum(target.toDouble())
                    is RowanValue.RNum -> target
                    else -> RowanValue.RNum(0.0)
                }
                "string" -> RowanValue.RString(target.toString().trim('"'))
                else -> target
            }
        },
        "eval" to { args, memory, byteMemory, outputBuffer ->
            val expr = args.getOrNull(0) ?: RowanValue.RNull
            when (expr) {
                is RowanValue.RList -> {
                    // Создаем временный env для eval, чтобы не засорять текущий
                    eval(expr, Environment()) 
                }
                is RowanValue.RString -> {
                    val tokens = tokenize(expr.v)
                    val parsed = parse(tokens)
                    // Если выражений несколько, оборачиваем в do
                    val astToRun = if (parsed.size > 1) listOf("do") + parsed else parsed
                    eval(astToRun, Environment())
                }
                else -> expr
            }
        },
        "syscall" to { args, _, _, _ ->
            val id = getLong(args.getOrNull(0))
            val callArgs = args.drop(1)
            syscallHandler?.invoke(id, callArgs) ?: RowanValue.RString("syscall($id) unhandled")
        }
    )
}
