package es.zelliot.perceptron

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.random.Random

object RowanEngine {
    private val memory = mutableMapOf<Long, RowanValue>()
    private val secureRandom = SecureRandom()

    fun evaluate(script: String): String {
        memory.clear()
        val chunks = script.split("---").map { it.trim() }.filter { it.isNotEmpty() }
        val results = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            if (chunks.size > 1) results.add("▶ ЗАПУСК БЛОКА ${index + 1} из ${chunks.size}")
            try {
                val tokens = tokenize(chunk)
                if (tokens.isEmpty()) continue
                val ast = parse(tokens)
                val env = Environment()
                registerBuiltins(env)
                var lastResult: RowanValue = RowanValue.RNull
                for (expr in ast) { lastResult = eval(expr, env) }
                val resStr = lastResult.toString()
                if (resStr != "null" && resStr.isNotEmpty() && !resStr.startsWith("Class ")) {
                    results.add(resStr)
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
        data class RObject(val className: String, val fields: MutableMap<String, RowanValue>, val methods: Map<String, MethodDef>) : RowanValue() {
            override fun toString() = "<Object:$className>"
        }
        data class RFunction(val params: List<String>, val body: List<Any?>, val closure: Environment) : RowanValue() {
            override fun toString() = "<fn>"
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

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                ' ', '\t', '\n', '\r' -> i++
                '#', ';' -> { while (i < input.length && input[i] != '\n') i++ } // ИСПРАВЛЕНО: поддержка ; для комментариев
                '(', ')' -> { tokens.add(c.toString()); i++ }
                '"' -> {
                    var j = i + 1; val sb = StringBuilder()
                    while (j < input.length && input[j] != '"') {
                        if (input[j] == '\\' && j + 1 < input.length) { sb.append(input[j+1]); j += 2 }
                        else { sb.append(input[j]); j++ }
                    }
                    tokens.add("\"${sb}\""); i = j + 1
                }
                else -> {
                    var j = i
                    while (j < input.length && !" \t\n\r()\"#;".contains(input[j])) j++
                    val tok = input.substring(i, j)
                    if (tok.isNotEmpty()) tokens.add(tok) // Защита от пустых токенов
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

    private fun eval(ast: Any?, env: Environment): RowanValue {
        return when (ast) {
            is List<*> -> {
                if (ast.isEmpty()) RowanValue.RNull
                else {
                    val first = ast[0] as? String ?: return RowanValue.RString("Error: Invalid list head (expected symbol)")
                    when (first) {
                        "do" -> { var res: RowanValue = RowanValue.RNull; for (i in 1 until ast.size) res = eval(ast[i], env); res }
                        "set" -> { val v = eval(ast[2], env); env.set(ast[1] as String, v); v }
                        "let" -> { val newEnv = env.extend(); val v = eval(ast[2], newEnv); newEnv.set(ast[1] as String, v); v }
                        "if" -> { val cond = eval(ast[1], env); if (isTruthy(cond)) eval(ast[2], env) else if (ast.size > 3) eval(ast[3], env) else RowanValue.RNull }
                        "while" -> { 
                            var res: RowanValue = RowanValue.RNull
                            while (isTruthy(eval(ast[1], env))) { 
                                try { res = eval(ast[2], env) } 
                                catch (e: ControlFlow) { if (e.type == "break") break; if (e.type == "continue") continue; else throw e } 
                            }
                            res 
                        }
                        "fn" -> {
                            val params = (ast[1] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                            val body = ast.subList(2, ast.size)
                            RowanValue.RFunction(params, body, env.extend())
                        }
                        "class" -> {
                            val name = ast[1] as? String ?: "Unknown"
                            val methods = mutableMapOf<String, MethodDef>()
                            for (i in 2 until ast.size) {
                                val methodDef = ast[i] as? List<*> ?: continue
                                val mName = methodDef[0] as? String ?: continue
                                // ИСПРАВЛЕНО: params берутся из второго элемента, body - всё что после
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
                                // ИСПРАВЛЕНО: передача аргументов в конструктор
                                val newArgs = ast.subList(2, ast.size).mapNotNull { eval(it, env) }
                                val initEnv = Environment().apply { 
                                    set("self", instance)
                                    initMethod.params.forEachIndexed { idx, p -> set(p, newArgs.getOrElse(idx) { RNull }) }
                                }
                                for (expr in initMethod.body) { eval(expr, initEnv) }
                            }
                            instance
                        }
                        "call" -> {
                            val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object")
                            val methodName = ast[2] as? String ?: throw Exception("Method name must be a symbol")
                            val methodDef = obj.methods[methodName] ?: throw Exception("Method '$methodName' not found")
                            // ИСПРАВЛЕНО: передача аргументов в метод
                            val callArgs = ast.subList(3, ast.size).mapNotNull { eval(it, env) }
                            val callEnv = Environment().apply { 
                                set("self", obj)
                                methodDef.params.forEachIndexed { idx, p -> set(p, callArgs.getOrElse(idx) { RNull }) }
                            }
                            var res: RowanValue = RowanValue.RNull
                            for (expr in methodDef.body) { res = eval(expr, callEnv) }
                            res
                        }
                        "get" -> { val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object"); obj.fields[ast[2] as String] ?: RowanValue.RNull }
                        "put" -> { val obj = eval(ast[1], env) as? RowanValue.RObject ?: throw Exception("Not an object"); obj.fields[ast[2] as String] = eval(ast[3], env); RowanValue.RNull }
                        "break" -> throw ControlFlow("break")
                        "continue" -> throw ControlFlow("continue")
                        else -> { 
                            val func = if (env.has(first)) env.get(first) else getBuiltin(first)
                            val args = ast.subList(1, ast.size).mapNotNull { eval(it, env) }
                            when (func) {
                                is RowanValue.RFunction -> {
                                    val callEnv = func.closure.extend()
                                    func.params.forEachIndexed { i, p -> callEnv.set(p, args.getOrElse(i) { RowanValue.RNull }) }
                                    var res: RowanValue = RowanValue.RNull
                                    for (expr in func.body) { res = eval(expr, callEnv) }
                                    res
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

    private fun isTruthy(v: RowanValue): Boolean = v !is RowanValue.RNull && v != RowanValue.RNum(0.0) && v != RowanValue.RString("false")
    class ControlFlow(val type: String) : Exception()

    private fun getBuiltin(name: String): RowanValue = builtins[name]?.let { RowanValue.RBuiltin(name, it) } ?: RowanValue.RString("Error: Unknown builtin '$name'")
    private fun registerBuiltins(env: Environment) { builtins.forEach { (name, fn) -> env.set(name, RowanValue.RBuiltin(name, fn)) } }

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

    private val builtins: Map<String, (List<RowanValue>) -> RowanValue> = mapOf(
        "+" to { args -> mathOp(args, { a, b -> a + b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 + n2 * d1, d1 * d2) }) },
        "-" to { args -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(-(args[0] as RowanValue.RNum).v)
            else mathOp(args, { a, b -> a - b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 - n2 * d1, d1 * d2) }) 
        },
        "*" to { args -> mathOp(args, { a, b -> a * b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * n2, d1 * d2) }) },
        "/" to { args -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(1.0 / (args[0] as RowanValue.RNum).v) 
            else if (args.size == 1 && args[0] is RowanValue.RRat) (args[0] as RowanValue.RRat).let { r -> RowanValue.RRat(r.den, r.num).simplify() }
            else mathOp(args.drop(1), { a, b -> a / b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2, d1 * n2) })
        },
        "rat" to { args -> RowanValue.RRat(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).simplify() },
        "float" to { args -> RowanValue.RNum(getNum(args.getOrNull(0))) },
        "&" to { args -> RowanValue.RNum((getLong(args.getOrNull(0)) and getLong(args.getOrNull(1))).toDouble()) },
        "|" to { args -> RowanValue.RNum((getLong(args.getOrNull(0)) or getLong(args.getOrNull(1))).toDouble()) },
        "^" to { args -> RowanValue.RNum((getLong(args.getOrNull(0)) xor getLong(args.getOrNull(1))).toDouble()) },
        "<<" to { args -> RowanValue.RNum((getLong(args.getOrNull(0)) shl getLong(args.getOrNull(1)).toInt()).toDouble()) },
        ">>" to { args -> RowanValue.RNum((getLong(args.getOrNull(0)) shr getLong(args.getOrNull(1)).toInt()).toDouble()) },
        "count-bits" to { args -> RowanValue.RNum(getLong(args.getOrNull(0)).countOneBits().toDouble()) },
        "==" to { args -> if (args.getOrNull(0).toString() == args.getOrNull(1).toString()) RowanValue.RNum(1.0) else RowanValue.RNull },
        "!=" to { args -> if (args.getOrNull(0).toString() != args.getOrNull(1).toString()) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<" to { args -> if (getNum(args.getOrNull(0)) < getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">" to { args -> if (getNum(args.getOrNull(0)) > getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<=" to { args -> if (getNum(args.getOrNull(0)) <= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">=" to { args -> if (getNum(args.getOrNull(0)) >= getNum(args.getOrNull(1))) RowanValue.RNum(1.0) else RowanValue.RNull },
        "and" to { args -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull) && isTruthy(args.getOrNull(1) ?: RowanValue.RNull)) (args.getOrNull(1) ?: RowanValue.RNull) else RowanValue.RNull },
        "or" to { args -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) (args.getOrNull(0) ?: RowanValue.RNull) else (args.getOrNull(1) ?: RowanValue.RNull) },
        "not" to { args -> if (isTruthy(args.getOrNull(0) ?: RowanValue.RNull)) RowanValue.RNull else RowanValue.RNum(1.0) },
        "store" to { args -> memory[getLong(args.getOrNull(0))] = args.getOrNull(1) ?: RowanValue.RNull; args.getOrNull(1) ?: RowanValue.RNull },
        "load" to { args -> memory[getLong(args.getOrNull(0))] ?: RowanValue.RNum(0.0) },
        "alloc" to { args -> 
            val addr = (memory.keys.maxOrNull() ?: -1L) + 1L
            val count = getLong(args.getOrNull(0)).toInt().coerceAtLeast(0)
            for(i in 0 until count) memory[addr+i] = RowanValue.RNull
            RowanValue.RNum(addr.toDouble()) 
        },
        "free" to { args -> 
            val count = getLong(args.getOrNull(0)).toInt().coerceAtLeast(0)
            val start = getLong(args.getOrNull(1))
            for(i in 0 until count) memory.remove(start + i)
            RowanValue.RNull 
        },
        "ptr" to { _ -> RowanValue.RNum(((memory.keys.maxOrNull() ?: -1L) + 1L).toDouble()) },
        "random" to { args -> 
            if (args.size >= 2) RowanValue.RNum(Random.nextDouble(getNum(args[0]), getNum(args[1])))
            else RowanValue.RNum(Random.nextDouble())
        },
        "secrandom" to { _ -> RowanValue.RNum(secureRandom.nextDouble()) },
        "randint" to { args -> RowanValue.RNum(Random.nextLong(getLong(args.getOrNull(0)), getLong(args.getOrNull(1))).toDouble()) },
        "list" to { args -> RowanValue.RList(args.toMutableList()) },
        "nth" to { args -> (args.getOrNull(0) as? RowanValue.RList)?.v?.getOrNull(getLong(args.getOrNull(1)).toInt()) ?: RowanValue.RNull },
        "append" to { args -> (args.getOrNull(0) as? RowanValue.RList)?.v?.add(args.getOrNull(1) ?: RowanValue.RNull); args.getOrNull(0) ?: RowanValue.RNull },
        "len" to { args -> RowanValue.RNum((args.getOrNull(0) as? RowanValue.RList)?.v?.size?.toDouble() ?: 0.0) },
        "print" to { args -> RowanValue.RString(args.joinToString(" ") { it.toString().trim('"') }) },
        "type" to { args -> 
            RowanValue.RString(when(args.getOrNull(0)) { 
                is RowanValue.RNum -> "number"; is RowanValue.RRat -> "rational"; is RowanValue.RString -> "string"
                is RowanValue.RList -> "list"; is RowanValue.RObject -> "object"; is RowanValue.RFunction -> "function"
                is RowanValue.RBuiltin -> "builtin"; else -> "null" 
            }) 
        },
        "cast" to { args ->
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
        }
    )
}
