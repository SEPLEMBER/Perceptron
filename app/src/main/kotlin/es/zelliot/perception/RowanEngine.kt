package es.zelliot.perceptron

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.random.Random

object RowanEngine {
    private val memory = mutableMapOf<Long, RowanValue>()
    private val secureRandom = SecureRandom()

    fun evaluate(script: String): String {
        memory.clear() 
        return try {
            val tokens = tokenize(script)
            val ast = parse(tokens)
            val env = Environment()
            registerBuiltins(env)
            val result = eval(ast, env)
            result.toString()
        } catch (e: Exception) {
            "Runtime Error: ${e.message}"
        }
    }

    // --- 1. ОПТИМИЗИРОВАННЫЕ ТИПЫ ДАННЫХ ---
    sealed class RowanValue {
        object RNull : RowanValue() { override fun toString() = "null" }
        
        data class RNum(val v: Double) : RowanValue() { 
            override fun toString() = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString() 
        }
        
        data class RRat(val num: Long, val den: Long) : RowanValue() {
            init { require(den != 0L) { "Division by zero" } }
            fun simplify(): RRat {
                val g = gcd(abs(num), abs(den))
                val sign = if (den < 0) -1L else 1L
                return RRat((num / g) * sign, abs(den) / g)
            }
            override fun toString() = if (den == 1L) num.toString() else "$num/$den"
            fun toDouble() = num.toDouble() / den.toDouble()
            private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        }

        data class RString(val v: String) : RowanValue() { override fun toString() = v }
        data class RList(val v: MutableList<RowanValue>) : RowanValue() { override fun toString() = "(${v.joinToString(" ")})" }
        data class RObject(val className: String, val fields: MutableMap<String, RowanValue>, val methods: Map<String, List<Any>>) : RowanValue() {
            override fun toString() = "<Object:$className>"
        }
        data class RFunction(val params: List<String>, val body: List<Any>, val closure: Environment) : RowanValue() {
            override fun toString() = "<fn>"
        }
    }

    // --- 2. ОКРУЖЕНИЕ ---
    class Environment(val parent: Environment? = null) {
        private val vars = mutableMapOf<String, RowanValue>()
        fun set(name: String, value: RowanValue) { vars[name] = value }
        fun get(name: String): RowanValue = vars[name] ?: parent?.get(name) ?: throw Exception("Undefined: $name")
        fun has(name: String): Boolean = vars.containsKey(name) || (parent?.has(name) ?: false) // ИСПРАВЛЕНО: доступ к проверке
        fun extend(): Environment = Environment(this)
    }

    // --- 3. ТОКЕНИЗАТОР ---
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                ' ', '\t', '\n', '\r' -> i++
                '#' -> { while (i < input.length && input[i] != '\n') i++ }
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
                    while (j < input.length && !" \t\n\r()\"#".contains(input[j])) j++
                    tokens.add(input.substring(i, j)); i = j
                }
            }
        }
        return tokens
    }

    // --- 4. ПАРСЕР ---
    private fun parse(tokens: List<String>): List<Any> {
        var pos = 0
        fun read(): Any {
            if (pos >= tokens.size) throw Exception("Unexpected EOF")
            val token = tokens[pos++]
            return when (token) {
                "(" -> {
                    val list = mutableListOf<Any>()
                    while (pos < tokens.size && tokens[pos] != ")") list.add(read())
                    if (pos < tokens.size && tokens[pos] == ")") pos++
                    list
                }
                ")" -> throw Exception("Unexpected ')'")
                else -> parseAtom(token)
            }
        }
        val ast = mutableListOf<Any>()
        while (pos < tokens.size) ast.add(read())
        return ast
    }

    private fun parseAtom(token: String): Any {
        if (token.startsWith("\"") && token.endsWith("\"")) return RowanValue.RString(token.substring(1, token.length - 1))
        if (token.contains("/") && token.count { it == '/' } == 1 && token.replace("-", "").replace("/", "").all { it.isDigit() }) {
            val parts = token.split("/")
            return RowanValue.RRat(parts[0].toLong(), parts[1].toLong()).simplify()
        }
        if (token.matches(Regex("-?\\d+(\\.\\d+)?"))) {
            return RowanValue.RNum(token.toDouble())
        }
        return token
    }

    // --- 5. ВЫЧИСЛИТЕЛЬ (EVALUATOR) ---
    // ИСПРАВЛЕНО: ast теперь Any?, чтобы корректно обрабатывать элементы List<*>
    private fun eval(ast: Any?, env: Environment): RowanValue {
        return when (ast) {
            is List<*> -> {
                if (ast.isEmpty()) RowanValue.RNull
                else {
                    val first = ast[0] as? String ?: throw Exception("Invalid call: $ast")
                    when (first) {
                        "do" -> { var res: RowanValue = RowanValue.RNull; for (i in 1 until ast.size) res = eval(ast[i], env); res }
                        "set" -> { val v = eval(ast[2], env); env.set(ast[1] as String, v); v }
                        "let" -> { val newEnv = env.extend(); val v = eval(ast[2], newEnv); newEnv.set(ast[1] as String, v); v }
                        "quote" -> RowanValue.RList(ast.subList(1, ast.size).mapNotNull { it as? String ?: (it as? List<*>) }.toMutableList())
                        "if" -> { val cond = eval(ast[1], env); if (isTruthy(cond)) eval(ast[2], env) else if (ast.size > 3) eval(ast[3], env) else RowanValue.RNull }
                        "while" -> { 
                            var res: RowanValue = RowanValue.RNull
                            while (isTruthy(eval(ast[1], env))) { 
                                try { res = eval(ast[2], env) } 
                                catch (e: ControlFlow) { if (e.type == "break") break; if (e.type == "continue") continue; else throw e } 
                            }
                            res 
                        }
                        "fn" -> RowanValue.RFunction((ast[1] as List<*>).map { it as String }, ast.subList(2, ast.size), env.extend())
                        "class" -> {
                            val name = ast[1] as String
                            val methods = mutableMapOf<String, List<Any>>()
                            for (i in 2 until ast.size) {
                                val methodDef = ast[i] as List<*>
                                methods[methodDef[0] as String] = methodDef.subList(1, methodDef.size)
                            }
                            env.set(name, RowanValue.RObject(name, mutableMapOf(), methods))
                            RowanValue.RString("Class $name defined")
                        }
                        "new" -> {
                            val cls = env.get(ast[1] as String) as RowanValue.RObject
                            val instance = RowanValue.RObject(cls.className, mutableMapOf(), cls.methods)
                            if (cls.methods.containsKey("init")) {
                                val initEnv = Environment().apply { set("self", instance) }
                                eval(cls.methods["init"]!!, initEnv)
                            }
                            instance
                        }
                        "get" -> { val obj = eval(ast[1], env) as RowanValue.RObject; obj.fields[ast[2] as String] ?: RowanValue.RNull }
                        "put" -> { val obj = eval(ast[1], env) as RowanValue.RObject; obj.fields[ast[2] as String] = eval(ast[3], env); RowanValue.RNull }
                        "break" -> throw ControlFlow("break")
                        "continue" -> throw ControlFlow("continue")
                        else -> { 
                            val func = if (env.has(first)) env.get(first) else getBuiltin(first)
                            val args = ast.subList(1, ast.size).map { eval(it, env) }
                            when (func) {
                                is RowanValue.RFunction -> {
                                    val callEnv = func.closure.extend()
                                    func.params.forEachIndexed { i, p -> callEnv.set(p, args.getOrElse(i) { RowanValue.RNull }) }
                                    eval(func.body, callEnv)
                                }
                                is BuiltinFunc -> func.fn(args)
                                else -> throw Exception("Not a function: $first")
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

    // --- 6. ВСТРОЕННЫЕ ФУНКЦИИ ---
    private fun getBuiltin(name: String): BuiltinFunc = builtins[name] ?: throw Exception("Unknown: $name")
    private fun registerBuiltins(env: Environment) { builtins.forEach { (name, func) -> env.set(name, func) } }
    private data class BuiltinFunc(val fn: (List<RowanValue>) -> RowanValue)

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
        return RowanValue.RNum(args.drop(1).fold((args[0] as RowanValue.RNum).v) { acc, v -> 
            doubleOp(acc, when(v) { is RowanValue.RNum -> v.v; is RowanValue.RRat -> v.toDouble(); else -> 0.0 }) 
        })
    }

    private val builtins = mapOf(
        "+" to BuiltinFunc { args -> mathOp(args, { a, b -> a + b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 + n2 * d1, d1 * d2) }) },
        "-" to BuiltinFunc { args -> 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(-(args[0] as RowanValue.RNum).v)
            else mathOp(args, { a, b -> a - b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2 - n2 * d1, d1 * d2) }) 
        },
        "*" to BuiltinFunc { args -> mathOp(args, { a, b -> a * b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * n2, d1 * d2) }) },
        "/" to BuiltinFunc { args -> mathOp(args.drop(1), { a, b -> a / b }, { n1, d1, n2, d2 -> RowanValue.RRat(n1 * d2, d1 * n2) }).let { 
            if (args.size == 1 && args[0] is RowanValue.RNum) RowanValue.RNum(1.0 / (args[0] as RowanValue.RNum).v) 
            else if (args.size == 1 && args[0] is RowanValue.RRat) (args[0] as RowanValue.RRat).let { r -> RowanValue.RRat(r.den, r.num).simplify() }
            else it 
        }},
        
        "rat" to BuiltinFunc { args -> RowanValue.RRat((args[0] as RowanValue.RNum).v.toLong(), (args[1] as RowanValue.RNum).v.toLong()).simplify() },
        "float" to BuiltinFunc { args -> RowanValue.RNum(when(args[0]) { is RowanValue.RRat -> args[0].toDouble(); is RowanValue.RNum -> args[0].v; else -> 0.0 }) },

        // ИСПРАВЛЕНО: корректное приведение типов для битовых операций
        "&" to BuiltinFunc { args -> RowanValue.RNum(((args[0] as RowanValue.RNum).v.toLong() and (args[1] as RowanValue.RNum).v.toLong()).toDouble()) },
        "|" to BuiltinFunc { args -> RowanValue.RNum(((args[0] as RowanValue.RNum).v.toLong() or (args[1] as RowanValue.RNum).v.toLong()).toDouble()) },
        "^" to BuiltinFunc { args -> RowanValue.RNum(((args[0] as RowanValue.RNum).v.toLong() xor (args[1] as RowanValue.RNum).v.toLong()).toDouble()) },
        "<<" to BuiltinFunc { args -> RowanValue.RNum(((args[0] as RowanValue.RNum).v.toLong() shl (args[1] as RowanValue.RNum).v.toInt()).toDouble()) },
        ">>" to BuiltinFunc { args -> RowanValue.RNum(((args[0] as RowanValue.RNum).v.toLong() shr (args[1] as RowanValue.RNum).v.toInt()).toDouble()) },
        "count-bits" to BuiltinFunc { args -> RowanValue.RNum((args[0] as RowanValue.RNum).v.toLong().countOneBits().toDouble()) },

        "==" to BuiltinFunc { args -> if (args[0].toString() == args[1].toString()) RowanValue.RNum(1.0) else RowanValue.RNull },
        "!=" to BuiltinFunc { args -> if (args[0].toString() != args[1].toString()) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<" to BuiltinFunc { args -> if (toNum(args[0]) < toNum(args[1])) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">" to BuiltinFunc { args -> if (toNum(args[0]) > toNum(args[1])) RowanValue.RNum(1.0) else RowanValue.RNull },
        "<=" to BuiltinFunc { args -> if (toNum(args[0]) <= toNum(args[1])) RowanValue.RNum(1.0) else RowanValue.RNull },
        ">=" to BuiltinFunc { args -> if (toNum(args[0]) >= toNum(args[1])) RowanValue.RNum(1.0) else RowanValue.RNull },
        "and" to BuiltinFunc { args -> if (isTruthy(args[0]) && isTruthy(args[1])) args[1] else RowanValue.RNull },
        "or" to BuiltinFunc { args -> if (isTruthy(args[0])) args[0] else args[1] },
        "not" to BuiltinFunc { args -> if (isTruthy(args[0])) RowanValue.RNull else RowanValue.RNum(1.0) },

        "store" to BuiltinFunc { args -> memory[(args[0] as RowanValue.RNum).v.toLong()] = args[1]; args[1] },
        "load" to BuiltinFunc { args -> memory[(args[0] as RowanValue.RNum).v.toLong()] ?: RowanValue.RNum(0.0) },
        "alloc" to BuiltinFunc { args -> val addr = (memory.keys.maxOrNull() ?: -1L) + 1L; for(i in 0 until (args[0] as RowanValue.RNum).v.toLong()) memory[addr+i] = RowanValue.RNull; RowanValue.RNum(addr.toDouble()) },
        "free" to BuiltinFunc { args -> for(i in 0 until (args[0] as RowanValue.RNum).v.toLong()) memory.remove((args[1] as RowanValue.RNum).v.toLong() + i); RowanValue.RNull },
        "ptr" to BuiltinFunc { args -> RowanValue.RNum(((memory.keys.maxOrNull() ?: -1L) + 1L).toDouble()) },

        "random" to BuiltinFunc { args -> 
            if (args.size == 2) RowanValue.RNum(Random.nextDouble((args[0] as RowanValue.RNum).v, (args[1] as RowanValue.RNum).v))
            else RowanValue.RNum(Random.nextDouble())
        },
        "secrandom" to BuiltinFunc { args -> RowanValue.RNum(secureRandom.nextDouble()) },
        "randint" to BuiltinFunc { args -> RowanValue.RNum(Random.nextLong((args[0] as RowanValue.RNum).v.toLong(), (args[1] as RowanValue.RNum).v.toLong()).toDouble()) },

        "list" to BuiltinFunc { args -> RowanValue.RList(args.toMutableList()) },
        "nth" to BuiltinFunc { args -> (args[0] as RowanValue.RList).v[(args[1] as RowanValue.RNum).v.toInt()] },
        "append" to BuiltinFunc { args -> (args[0] as RowanValue.RList).v.add(args[1]); args[0] },
        "len" to BuiltinFunc { args -> RowanValue.RNum((args[0] as RowanValue.RList).v.size.toDouble()) },
        "print" to BuiltinFunc { args -> RowanValue.RString(args.joinToString(" ") { it.toString() }) },
        "type" to BuiltinFunc { args -> RowanValue.RString(when(args[0]) { is RowanValue.RNum -> "number"; is RowanValue.RRat -> "rational"; is RowanValue.RString -> "string"; is RowanValue.RList -> "list"; is RowanValue.RObject -> "object"; is RowanValue.RFunction -> "function"; else -> "null" }) },
        "cast" to BuiltinFunc { args -> 
            val v = args[0]; val t = (args[1] as RowanValue.RString).v
            when(t) {
                // ИСПРАВЛЕНО: v.v.toDoubleOrNull() для корректного доступа к строковому значению
                "number" -> if (v is RowanValue.RString) RowanValue.RNum(v.v.toDoubleOrNull() ?: 0.0) else if (v is RowanValue.RRat) RowanValue.RNum(v.toDouble()) else v
                "string" -> RowanValue.RString(v.toString())
                else -> v
            }
        }
    )

    private fun toNum(v: RowanValue): Double = when(v) { is RowanValue.RNum -> v.v; is RowanValue.RRat -> v.toDouble(); else -> 0.0 }
}
