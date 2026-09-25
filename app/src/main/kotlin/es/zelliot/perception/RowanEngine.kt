package es.zelliot.perceptron

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.random.Random

object RowanEngine {
    var syscallHandler: ((Long, List<RowanValue>) -> RowanValue)? = null
    
    private const val MAX_ITERATIONS = 100_000
    private const val MAX_MEMORY_OPS = 10_000_000

    fun evaluate(script: String): String {
        val memory = mutableMapOf<Long, RowanValue>()
        val byteMemory = mutableMapOf<Long, Byte>()
        val outputBuffer = StringBuilder()
        
        val chunks = script.split("---").map { it.trim() }.filter { it.isNotEmpty() }
        val results = mutableListOf<String>()

        fun eval(ast: Any?, env: Environment, depth: Int = 0): RowanValue {
            if (depth > 1000) throw Exception("Stack overflow: recursion too deep")
            
            return when (ast) {
                is List<*> -> {
                    if (ast.isEmpty()) RowanValue.RNull
                    else {
                        val first = ast[0] as? String ?: return RowanValue.RString("Error: Invalid list head")
                        when (first) {
                            "do" -> { 
                                var res: RowanValue = RowanValue.RNull
                                for (i in 1 until ast.size) res = eval(ast[i], env, depth + 1)
                                res 
                            }
                            "set" -> { 
                                val name = ast.getOrNull(1) as? String ?: return RowanValue.RString("Error: set requires symbol")
                                val value = eval(ast.getOrNull(2), env, depth + 1)
                                env.set(name, value)
                                value 
                            }
                            "let" -> { 
                                if (ast.size >= 4) {
                                    val name = ast.getOrNull(1) as? String ?: return RowanValue.RString("Error: let requires symbol")
                                    val value = eval(ast.getOrNull(2), env, depth + 1)
                                    val newEnv = env.extend()
                                    newEnv.set(name, value)
                                    var res: RowanValue = RowanValue.RNull
                                    for (i in 3 until ast.size) res = eval(ast.getOrNull(i), newEnv, depth + 1)
                                    res
                                } else {
                                    val name = ast.getOrNull(1) as? String ?: return RowanValue.RString("Error: let requires symbol")
                                    val value = eval(ast.getOrNull(2), env, depth + 1)
                                    env.set(name, value)
                                    value
                                }
                            }
                            "if" -> { 
                                val cond = eval(ast.getOrNull(1), env, depth + 1)
                                if (isTruthy(cond)) eval(ast.getOrNull(2), env, depth + 1) 
                                else if (ast.size > 3) eval(ast.getOrNull(3), env, depth + 1) 
                                else RowanValue.RNull 
                            }
                            "while" -> { 
                                var res: RowanValue = RowanValue.RNull
                                var iterations = 0
                                while (isTruthy(eval(ast.getOrNull(1), env, depth + 1))) { 
                                    if (++iterations > MAX_ITERATIONS) throw Exception("Infinite loop detected")
                                    try { res = eval(ast.getOrNull(2), env, depth + 1) } 
                                    catch (e: ControlFlow) { if (e.type == "break") break; if (e.type == "continue") continue; else throw e } 
                                }
                                res 
                            }
                            "return" -> throw ControlFlow("return", eval(ast.getOrNull(1), env, depth + 1))
                            "halt" -> throw ControlFlow("halt", eval(ast.getOrNull(1), env, depth + 1))
                            "yield" -> throw ControlFlow("yield", eval(ast.getOrNull(1), env, depth + 1))
                            
                            // ИСПРАВЛЕНО: Функции теперь сохраняются в ТЕКУЩИЙ env, а не в newEnv
                            "fn" -> {
                                if (ast.size >= 4 && ast[1] is String && ast[2] is List<*>) {
                                    val name = ast[1] as String
                                    val params = (ast[2] as List<*>).filterIsInstance<String>()
                                    val body = ast.subList(3, ast.size)
                                    val closureEnv = env.extend()
                                    val func = RowanValue.RFunction(name, params, body, closureEnv)
                                    env.set(name, func) // ← ИСПРАВЛЕНО: сохраняем в текущий env
                                    func
                                } else {
                                    val params = (ast.getOrNull(1) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                                    val body = ast.subList(2, ast.size)
                                    RowanValue.RFunction(null, params, body, env.extend())
                                }
                            }
                            "class" -> {
                                val name = ast.getOrNull(1) as? String ?: return RowanValue.RString("Error: class requires name")
                                val methods = mutableMapOf<String, MethodDef>()
                                for (i in 2 until ast.size) {
                                    val methodDef = ast[i] as? List<*> ?: continue
                                    val mName = methodDef.getOrNull(0) as? String ?: continue
                                    val params = (methodDef.getOrNull(1) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                                    val body = methodDef.subList(2, methodDef.size)
                                    methods[mName] = MethodDef(params, body)
                                }
                                env.set(name, RowanValue.RObject(name, mutableMapOf(), methods))
                                RowanValue.RString("Class '$name' defined")
                            }
                            "new" -> {
                                val clsName = ast.getOrNull(1) as? String ?: return RowanValue.RString("Error: new requires class name")
                                val cls = env.get(clsName) as? RowanValue.RObject ?: return RowanValue.RString("Error: Not a class: $clsName")
                                val instance = RowanValue.RObject(cls.className, mutableMapOf(), cls.methods)
                                val initMethod = cls.methods["init"]
                                if (initMethod != null) {
                                    val newArgs = ast.subList(2, ast.size).map { eval(it, env, depth + 1) }
                                    val initEnv = Environment().apply { 
                                        set("self", instance)
                                        initMethod.params.forEachIndexed { idx, p -> set(p, newArgs.getOrElse(idx) { RowanValue.RNull }) }
                                    }
                                    for (expr in initMethod.body) { eval(expr, initEnv, depth + 1) }
                                }
                                instance
                            }
                            "call" -> {
                                val obj = eval(ast.getOrNull(1), env, depth + 1) as? RowanValue.RObject ?: return RowanValue.RString("Error: Not an object")
                                val methodName = ast.getOrNull(2) as? String ?: return RowanValue.RString("Error: Method name must be a symbol")
                                val methodDef = obj.methods[methodName] ?: return RowanValue.RString("Error: Method '$methodName' not found")
                                val callArgs = ast.subList(3, ast.size).map { eval(it, env, depth + 1) }
                                val callEnv = Environment().apply { 
                                    set("self", obj)
                                    methodDef.params.forEachIndexed { idx, p -> set(p, callArgs.getOrElse(idx) { RowanValue.RNull }) }
                                }
                                var res: RowanValue = RowanValue.RNull
                                for (expr in methodDef.body) { res = eval(expr, callEnv, depth + 1) }
                                res
                            }
                            "get" -> { 
                                val obj = eval(ast.getOrNull(1), env, depth + 1) as? RowanValue.RObject ?: return RowanValue.RString("Error: Not an object")
                                val field = ast.getOrNull(2) as? String ?: return RowanValue.RString("Error: Field must be symbol")
                                obj.fields[field] ?: RowanValue.RNull 
                            }
                            "put" -> { 
                                val obj = eval(ast.getOrNull(1), env, depth + 1) as? RowanValue.RObject ?: return RowanValue.RString("Error: Not an object")
                                val field = ast.getOrNull(2) as? String ?: return RowanValue.RString("Error: Field must be symbol")
                                obj.fields[field] = eval(ast.getOrNull(3), env, depth + 1)
                                RowanValue.RNull 
                            }
                            "break" -> throw ControlFlow("break")
                            "continue" -> throw ControlFlow("continue")
                            
                            "match" -> {
                                val target = eval(ast.getOrNull(1), env, depth + 1)
                                var res: RowanValue = RowanValue.RNull
                                var i = 2
                                while (i < ast.size) {
                                    val pattern = ast.getOrNull(i) ?: break
                                    val body = ast.getOrNull(i + 1) ?: break
                                    val isDefault = (pattern is String && (pattern == "else" || pattern == "default"))
                                    val matches = isDefault || (eval(pattern, env, depth + 1) == target)
                                    
                                    if (matches) {
                                        res = eval(body, env, depth + 1)
                                        break
                                    }
                                    i += 2
                                }
                                res
                            }
                            
                            else -> { 
                                val func = if (env.has(first)) env.get(first) else getBuiltin(first, memory, byteMemory, outputBuffer, ::eval)
                                val args = ast.subList(1, ast.size).map { eval(it, env, depth + 1) }
                                when (func) {
                                    is RowanValue.RFunction -> {
                                        val callEnv = func.closure.extend()
                                        func.params.forEachIndexed { i, p -> callEnv.set(p, args.getOrElse(i) { RowanValue.RNull }) }
                                        try {
                                            var res: RowanValue = RowanValue.RNull
                                            for (expr in func.body) { res = eval(expr, callEnv, depth + 1) }
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
                registerBuiltins(env, memory, byteMemory, outputBuffer, ::eval)
                
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
        
        // ИСПРАВЛЕНО: Добавлена поддержка hex-чисел (0x12, 0xFF)
        if (token.startsWith("0x", ignoreCase = true) || token.startsWith("0X")) {
            return try {
                RowanValue.RNum(token.substring(2).toLong(16).toDouble())
            } catch (e: NumberFormatException) {
                token
            }
        }
        
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

    private fun getBuiltin(
        name: String, 
        memory: MutableMap<Long, RowanValue>, 
        byteMemory: MutableMap<Long, Byte>, 
        outputBuffer: StringBuilder,
        evalFn: (Any?, Environment, Int) -> RowanValue
    ): RowanValue {
        return builtins[name]?.let { RowanValue.RBuiltin(name, { args -> it(args, memory, byteMemory, outputBuffer, evalFn) }) } 
               ?: RowanValue.RString("Error: Unknown builtin '$name'")
    }

    private fun registerBuiltins(
        env: Environment, 
        memory: MutableMap<Long, RowanValue>, 
        byteMemory: MutableMap<Long, Byte>, 
        outputBuffer: StringBuilder,
        evalFn: (Any?, Environment, Int) -> RowanValue
    ) { 
        builtins.forEach { (name, fn) -> 
            env.set(name, RowanValue.RBuiltin(name, { args -> fn(args, memory, byteMemory, outputBuffer, evalFn) })) 
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

    private val builtins: Map<String, (List<RowanValue>, MutableMap<Long, RowanValue>, MutableMap<Long, Byte>, StringBuilder, (Any?, Environment, Int) -> RowanValue) -> RowanValue> = mapOf(
        "+" to { args, _, _, _, _ -> mathOp(args, { a, b -> a + b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 + n2 * d1, d1 * d2) }) },
        "-" to { args, _, _, _, _ -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(-(args[0] as RowanValue.RNum).v)
            else mathOp(args, { a, b -> a - b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 - n2 * d1, d1 * d2) }) 
        },
        "*" to { args, _, _, _, _ -> mathOp(args, { a, b -> a * b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * n2, d1 * d2) }) },
        "/" to { args, _, _, _, _ -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(1.0 / (args[0] as RowanValue.RNum).v) 
            else if (args.size == 1 && args[0] is RowanValue.RRat) (args[0] as RowanValue.RRat).let { r -> RowanValue.RRat(r.den, r.num).simplify() }
            else mathOp(args.drop(1), { a, b -> a / b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2, d1 * n2) })
        },
        "rat" to { args, _, _, _, _ -> RowanValue.RRat(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).simplify() },
        "float" to { args, _, _, _, _ -> RowanValue.RNum(getNum(args.getOrNull(0))) },
        
        "&" to { args, _, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) and getLong(args.getOrNull(1))).toDouble()) },
        "|" to { args, _, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) or getLong(args.getOrNull(1))).toDouble()) },
        "^" to { args, _, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) xor getLong(args.getOrNull(1))).toDouble()) },
        "~" to { args, _, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).inv().toDouble()) },
        "<<" to { args, _, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) shl getLong(args.getOrNull(1)).toInt()).toDouble()) },
        ">>" to { args, _, _, _, _ -> RowanValue.RNum((getLong(args.getOrNull(0)) shr getLong(args.getOrNull(1)).toInt()).toDouble()) },
        "rol" to { args, _, _, _, _ -> 
            val v = getLong(args.getOrNull(0))
            val shift = (getLong(args.getOrNull(1)) and 63L).toInt()
            val invShift = (64 - shift) and 63
            RowanValue.RNum(((v shl shift) or (v ushr invShift)).toDouble())
        },
        "ror" to { args, _, _, _, _ -> 
            val v = getLong(args.getOrNull(0))
            val shift = (getLong(args.getOrNull(1)) and 63L).toInt()
            val invShift = (64 - shift) and 63
            RowanValue.RNum(((v ushr shift) or (v shl invShift)).toDouble())
        },
        "count-bits" to { args, _, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countOneBits().toDouble()) },
        "clz" to { args, _, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countLeadingZeroBits().toDouble()) },
        "ctz" to { args, _, _, _, _ -> RowanValue.RNum(getLong(args.getOrNull(0)).countTrailingZeroBits().toDouble()) },

        "==" to { args, _, _, _, _ -> if (args.getOrNull(0) == args.getOrNull(1)) RowanValue.RNum(1.0) else RowanValue.RNull },
        "!=" to { args, _, _, _, _ -> if (args.getOrNull(0) != args.getOrNull(1)) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<" to { args, _, _, _, _ -> if (getNum(args.getOrNull(0)) < getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">" to { args, _, _, _, _ -> if (getNum(args.getOrNull(0)) > getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<=" to { args, _, _, _, _ -> if (getNum(args.getOrNull(0)) <= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">=" to { args, _, _, _, _ -> if (getNum(args.getOrNull(0)) >= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "and" to { args, _, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull) && isTruthy(args.getOrNull(1) ?: RowanValue.RNull)) (args.getOrNull(1) ?: RowanValue.RNull) else RowanValue.RNull },
        "or" to { args, _, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) (args.getOrNull(0) ?: RowanValue.RNull) else (args.getOrNull(1) ?: RowanValue.RNull) },
        "not" to { args, _, _, _, _ -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) RowanValue.RNull else RowanValue.RNum(1.0) },

        "store" to { args, memory, _, _, _ -> 
            val addr = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            memory[addr] = args.getOrNull(1) ?: RowanValue.RNull
            args.getOrNull(1) ?: RowanValue.RNull 
        },
        "load" to { args, memory, _, _, _ -> 
            val addr = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            memory[addr] ?: RowanValue.RNum(0.0) 
        },
        "alloc" to { args, memory, _, _, _ -> 
            val addr = ((memory.keys.maxOrNull() ?: -1L) + 1L).coerceAtLeast(0L)
            val count = getLong(args.getOrNull(0)).toInt().coerceIn(0, MAX_MEMORY_OPS)
            for(i in 0 until count) memory[addr+i] = RowanValue.RNull
            RowanValue.RNum(addr.toDouble()) 
        },
        "free" to { args, memory, _, _, _ -> 
            val count = getLong(args.getOrNull(0)).toInt().coerceIn(0, MAX_MEMORY_OPS)
            val start = getLong(args.getOrNull(1)).coerceAtLeast(0L)
            for(i in 0 until count) memory.remove(start + i)
            RowanValue.RNull 
        },
        "ptr" to { _, memory, _, _, _ -> RowanValue.RNum(((memory.keys.maxOrNull() ?: -1L) + 1L).coerceAtLeast(0L).toDouble()) },

        "byte" to { args, _, _, _, _ -> RowanValue.RBytes(byteArrayOf(getLong(args.getOrNull(0)).toByte())) },
        "bytes" to { args, _, _, _, _ -> 
            val size = getLong(args.getOrNull(0)).toInt().coerceIn(0, MAX_MEMORY_OPS)
            val fill = if (args.size > 1) getLong(args.getOrNull(1)).toByte() else 0.toByte()
            RowanValue.RBytes(ByteArray(size) { fill })
        },
        "load8" to { args, _, byteMemory, _, _ -> 
            val addr = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            RowanValue.RNum(byteMemory[addr]?.toUByte()?.toInt()?.toDouble() ?: 0.0)
        },
        "store8" to { args, _, byteMemory, _, _ -> 
            val addr = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            val value = getLong(args.getOrNull(1)).toByte()
            byteMemory[addr] = value
            RowanValue.RNum(value.toUByte().toInt().toDouble())
        },
        "memset" to { args, _, byteMemory, _, _ ->
            val addr = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            val value = getLong(args.getOrNull(1)).toByte()
            val count = getLong(args.getOrNull(2)).toInt().coerceIn(0, MAX_MEMORY_OPS)
            for (i in 0 until count) byteMemory[addr + i] = value
            RowanValue.RNull
        },
        "memcpy" to { args, _, byteMemory, _, _ ->
            val dst = getLong(args.getOrNull(0)).coerceAtLeast(0L)
            val src = getLong(args.getOrNull(1)).coerceAtLeast(0L)
            val count = getLong(args.getOrNull(2)).toInt().coerceIn(0, MAX_MEMORY_OPS)
            for (i in 0 until count) byteMemory[dst + i] = byteMemory[src + i] ?: 0.toByte()
            RowanValue.RNull
        },

        "random" to { args, _, _, _, _ -> 
            if (args.size >= 2) RowanValue.RNum(Random.nextDouble(getNum(args[0]), getNum(args[1])))
            else RowanValue.RNum(Random.nextDouble())
        },
        "secrandom" to { _, _, _, _, _ -> RowanValue.RNum(SecureRandom().nextDouble()) },
        "randint" to { args, _, _, _, _ -> RowanValue.RNum(Random.nextLong(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).toDouble()) },
        "list" to { args, _, _, _, _ -> RowanValue.RList(args.toMutableList()) },
        "nth" to { args, _, _, _, _ -> (args.getOrNull(0) as? RowanValue.RList)?.v?.getOrNull(getLong(args.getOrNull(1)).toInt()) ?: RowanValue.RNull },
        "append" to { args, _, _, _, _ -> (args.getOrNull(0) as? RowanValue.RList)?.v?.add(args.getOrNull(1) ?: RowanValue.RNull); args.getOrNull(0) ?: RowanValue.RNull },
        "len" to { args, _, _, _, _ -> RowanValue.RNum((args.getOrNull(0) as? RowanValue.RList)?.v?.size?.toDouble() ?: 0.0) },
        "print" to { args, _, _, outputBuffer, _ -> 
            val text = args.joinToString(" ") { it.toString().trim('"') }
            outputBuffer.append(text).append("\n")
            RowanValue.RString(text) 
        },
        "type" to { args, _, _, _, _ -> 
            RowanValue.RString(when(args.getOrNull(0)) { 
                is RowanValue.RNum -> "number"; is RowanValue.RRat -> "rational"; is RowanValue.RString -> "string"
                is RowanValue.RList -> "list"; is RowanValue.RObject -> "object"; is RowanValue.RFunction -> "function"
                is RowanValue.RBytes -> "bytes"; is RowanValue.RBuiltin -> "builtin"; else -> "null" 
            }) 
        },
        "cast" to { args, _, _, _, _ ->
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
        "eval" to { args, _, _, _, evalFn ->
            val expr = args.getOrNull(0) ?: RowanValue.RNull
            when (expr) {
                is RowanValue.RList -> evalFn(expr.v, Environment(), 0)
                is RowanValue.RString -> {
                    val tokens = tokenize(expr.v)
                    val parsed = parse(tokens)
                    val astToRun = if (parsed.size > 1) listOf("do") + parsed else parsed
                    evalFn(astToRun, Environment(), 0)
                }
                else -> expr
            }
        },
        "syscall" to { args, _, _, _, _ ->
            val id = getLong(args.getOrNull(0))
            val callArgs = args.drop(1)
            syscallHandler?.invoke(id, callArgs) ?: RowanValue.RString("syscall($id) unhandled")
        }
    )
}
