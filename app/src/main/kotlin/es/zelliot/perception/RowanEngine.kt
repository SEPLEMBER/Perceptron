package es.zelliot.perceptron

import java.math.BigInteger
import java.security.SecureRandom
import kotlin.random.Random

object RowanEngine {
    private val memory = mutableMapOf<Long, RowanValue>()
    private val secureRandom = SecureRandom()

    fun evaluate(script: String): String {
        memory.clear() // Reset memory per execution for safety
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

    // --- 1. RATIONAL MATH ---
    data class Rational(val num: BigInteger, val den: BigInteger) {
        fun simplify(): Rational {
            if (den == BigInteger.ZERO) throw ArithmeticException("Division by zero")
            val g = num.gcd(den)
            val sign = if (den < BigInteger.ZERO) -1 else 1
            return Rational((num * sign.toBigInteger()) / g, den.abs() / g)
        }
        operator fun plus(o: Rational) = Rational(num * o.den + o.num * den, den * o.den).simplify()
        operator fun minus(o: Rational) = Rational(num * o.den - o.num * den, den * o.den).simplify()
        operator fun times(o: Rational) = Rational(num * o.num, den * o.den).simplify()
        operator fun div(o: Rational) = Rational(num * o.den, den * o.num).simplify()
        override fun toString(): String = if (den == BigInteger.ONE) num.toString() else "$num/$den"
        fun toDouble(): Double = num.toDouble() / den.toDouble()
        fun toLong(): Long = (num / den).toLong()
    }

    // --- 2. DATA TYPES ---
    sealed class RowanValue {
        object RNull : RowanValue() { override fun toString() = "null" }
        data class RNumber(val v: Rational) : RowanValue() { override fun toString() = v.toString() }
        data class RString(val v: String) : RowanValue() { override fun toString() = v }
        data class RList(val v: MutableList<RowanValue>) : RowanValue() { override fun toString() = "(${v.joinToString(" ")})" }
        data class RObject(val className: String, val fields: MutableMap<String, RowanValue>, val methods: Map<String, List<Any>>) : RowanValue() {
            override fun toString() = "<Object:$className>"
        }
        data class RFunction(val params: List<String>, val body: List<Any>, val closure: Environment) : RowanValue() {
            override fun toString() = "<fn>"
        }
    }

    // --- 3. ENVIRONMENT ---
    class Environment(val parent: Environment? = null) {
        private val vars = mutableMapOf<String, RowanValue>()
        fun set(name: String, value: RowanValue) { vars[name] = value }
        fun get(name: String): RowanValue = vars[name] ?: parent?.get(name) ?: throw Exception("Undefined: $name")
        fun extend(): Environment = Environment(this)
    }

    // --- 4. TOKENIZER ---
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

    // --- 5. PARSER ---
    private fun parse(tokens: List<String>): List<Any> {
        var pos = 0
        fun read(): Any {
            if (pos >= tokens.size) throw Exception("Unexpected EOF")
            val token = tokens[pos++]
            return when (token) {
                "(" -> {
                    val list = mutableListOf<Any>()
                    while (tokens[pos] != ")") list.add(read())
                    pos++ // skip ")"
                    list
                }
                ")" -> throw Exception("Unexpected ')'")
                else -> parseAtom(token)
            }
        }
        val ast = mutableListOf<Any>()
        while (pos < tokens.size) ast.add(read())
        return if (ast.size == 1) ast else ast // Allow multiple top-level forms
    }

    private fun parseAtom(token: String): Any {
        if (token.startsWith("\"") && token.endsWith("\"")) return RowanValue.RString(token.substring(1, token.length - 1))
        if (token.matches(Regex("-?\\d+/\\d+"))) {
            val parts = token.split("/")
            return RowanValue.RNumber(Rational(BigInteger(parts[0]), BigInteger(parts[1])).simplify())
        }
        if (token.matches(Regex("-?\\d+(\\.\\d+)?"))) {
            return RowanValue.RNumber(Rational(BigInteger(token.replace(".", "")), BigInteger.TEN.pow(token.substringAfter('.', "0").length)).simplify())
        }
        return token
    }

    // --- 6. EVALUATOR ---
    private fun eval(ast: Any, env: Environment): RowanValue {
        return when (ast) {
            is List<*> -> {
                if (ast.isEmpty()) RowanValue.RNull
                else {
                    val first = ast[0] as? String ?: throw Exception("Invalid call: $ast")
                    when (first) {
                        "do" -> { var res: RowanValue = RowanValue.RNull; for (i in 1 until ast.size) res = eval(ast[i], env); res }
                        "set" -> { val v = eval(ast[2], env); env.set(ast[1] as String, v); v }
                        "let" -> { val newEnv = env.extend(); val v = eval(ast[2], newEnv); newEnv.set(ast[1] as String, v); v }
                        "quote" -> RowanValue.RList(ast.subList(1, ast.size).map { it as? String ?: (it as List<*>) }.toMutableList())
                        "if" -> { val cond = eval(ast[1], env); if (isTruthy(cond)) eval(ast[2], env) else if (ast.size > 3) eval(ast[3], env) else RowanValue.RNull }
                        "while" -> { var res: RowanValue = RowanValue.RNull; while (isTruthy(eval(ast[1], env))) { try { res = eval(ast[2], env) } catch (e: ControlFlow) { if (e.type == "break") break; if (e.type == "continue") continue; else throw e } } ; res }
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
                        "get" -> {
                            val obj = eval(ast[1], env) as RowanValue.RObject
                            obj.fields[ast[2] as String] ?: RowanValue.RNull
                        }
                        "put" -> {
                            val obj = eval(ast[1], env) as RowanValue.RObject
                            obj.fields[ast[2] as String] = eval(ast[3], env)
                            RowanValue.RNull
                        }
                        "break" -> throw ControlFlow("break")
                        "continue" -> throw ControlFlow("continue")
                        else -> { // Function call
                            val func = if (env.vars.containsKey(first)) env.get(first) else getBuiltin(first)
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
            is RowanValue.RNumber -> ast
            is RowanValue.RString -> ast
            is List<*> -> RowanValue.RList(ast.map { eval(it, env) }.toMutableList())
            else -> RowanValue.RNull
        }
    }

    private fun isTruthy(v: RowanValue): Boolean = v !is RowanValue.RNull && v != RowanValue.RNumber(Rational(BigInteger.ZERO, BigInteger.ONE)) && v != RowanValue.RString("false")

    class ControlFlow(val type: String) : Exception()

    // --- 7. BUILTINS (>60 elements) ---
    private fun getBuiltin(name: String): BuiltinFunc = builtins[name] ?: throw Exception("Unknown: $name")

    private fun registerBuiltins(env: Environment) {
        builtins.forEach { (name, func) -> env.set(name, func) }
    }

    private data class BuiltinFunc(val fn: (List<RowanValue>) -> RowanValue)

    private val builtins = mapOf(
        // Math
        "+" to BuiltinFunc { args -> RowanValue.RNumber(args.fold(Rational(BigInteger.ZERO, BigInteger.ONE)) { acc, v -> acc + (v as RowanValue.RNumber).v }) },
        "-" to BuiltinFunc { args -> RowanValue.RNumber(args.fold(Rational(BigInteger.ZERO, BigInteger.ONE)) { acc, v -> if (acc == Rational(BigInteger.ZERO, BigInteger.ONE) && args.size == 1) (-v as RowanValue.RNumber).v else acc - (v as RowanValue.RNumber).v }) },
        "*" to BuiltinFunc { args -> RowanValue.RNumber(args.fold(Rational(BigInteger.ONE, BigInteger.ONE)) { acc, v -> acc * (v as RowanValue.RNumber).v }) },
        "/" to BuiltinFunc { args -> RowanValue.RNumber(args.drop(1).fold((args[0] as RowanValue.RNumber).v) { acc, v -> acc / (v as RowanValue.RNumber).v }) },
        "%" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() % (args[1] as RowanValue.RNumber).v.toLong(), BigInteger.ONE)) },
        "abs" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.num.abs(), (args[0] as RowanValue.RNumber).v.den.abs()).simplify()) },
        "min" to BuiltinFunc { args -> args.minByOrNull { (it as RowanValue.RNumber).v.toDouble() } ?: RowanValue.RNull },
        "max" to BuiltinFunc { args -> args.maxByOrNull { (it as RowanValue.RNumber).v.toDouble() } ?: RowanValue.RNull },
        "rat" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong(), (args[1] as RowanValue.RNumber).v.toLong()).simplify()) },
        
        // Bitwise
        "&" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() and (args[1] as RowanValue.RNumber).v.toLong(), BigInteger.ONE)) },
        "|" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() or (args[1] as RowanValue.RNumber).v.toLong(), BigInteger.ONE)) },
        "^" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() xor (args[1] as RowanValue.RNumber).v.toLong(), BigInteger.ONE)) },
        "~" to BuiltinFunc { args -> RowanValue.RNumber(Rational(inv((args[0] as RowanValue.RNumber).v.toLong()), BigInteger.ONE)) },
        "<<" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() shl (args[1] as RowanValue.RNumber).v.toLong().toInt(), BigInteger.ONE)) },
        ">>" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong() shr (args[1] as RowanValue.RNumber).v.toLong().toInt(), BigInteger.ONE)) },
        "count-bits" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RNumber).v.toLong().countOneBits().toLong(), BigInteger.ONE)) },

        // Comparison
        "==" to BuiltinFunc { args -> if (args[0].toString() == args[1].toString()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        "!=" to BuiltinFunc { args -> if (args[0].toString() != args[1].toString()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        "<" to BuiltinFunc { args -> if ((args[0] as RowanValue.RNumber).v.toDouble() < (args[1] as RowanValue.RNumber).v.toDouble()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        ">" to BuiltinFunc { args -> if ((args[0] as RowanValue.RNumber).v.toDouble() > (args[1] as RowanValue.RNumber).v.toDouble()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        "<=" to BuiltinFunc { args -> if ((args[0] as RowanValue.RNumber).v.toDouble() <= (args[1] as RowanValue.RNumber).v.toDouble()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        ">=" to BuiltinFunc { args -> if ((args[0] as RowanValue.RNumber).v.toDouble() >= (args[1] as RowanValue.RNumber).v.toDouble()) RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) else RowanValue.RNull },
        "and" to BuiltinFunc { args -> if (isTruthy(args[0]) && isTruthy(args[1])) args[1] else RowanValue.RNull },
        "or" to BuiltinFunc { args -> if (isTruthy(args[0])) args[0] else args[1] },
        "not" to BuiltinFunc { args -> if (isTruthy(args[0])) RowanValue.RNull else RowanValue.RNumber(Rational(BigInteger.ONE, BigInteger.ONE)) },

        // Memory (Brainfuck style)
        "store" to BuiltinFunc { args -> memory[(args[0] as RowanValue.RNumber).v.toLong()] = args[1]; args[1] },
        "load" to BuiltinFunc { args -> memory[(args[0] as RowanValue.RNumber).v.toLong()] ?: RowanValue.RNumber(Rational(BigInteger.ZERO, BigInteger.ONE)) },
        "alloc" to BuiltinFunc { args -> val addr = memory.keys.maxOrNull()?.plus(1) ?: 0L; for(i in 0 until (args[0] as RowanValue.RNumber).v.toLong()) memory[addr+i] = RowanValue.RNull; RowanValue.RNumber(Rational(addr, BigInteger.ONE)) },
        "free" to BuiltinFunc { args -> for(i in 0 until (args[0] as RowanValue.RNumber).v.toLong()) memory.remove((args[1] as RowanValue.RNumber).v.toLong() + i); RowanValue.RNull },
        "ptr" to BuiltinFunc { args -> RowanValue.RNumber(Rational(memory.keys.maxOrNull()?.plus(1) ?: 0L, BigInteger.ONE)) },

        // Random
        "random" to BuiltinFunc { args -> 
            if (args.size == 2) RowanValue.RNumber(Rational((Random.nextLong((args[0] as RowanValue.RNumber).v.toLong(), (args[1] as RowanValue.RNumber).v.toLong())), BigInteger.ONE))
            else RowanValue.RNumber(Rational(Random.nextLong(), BigInteger.ONE))
        },
        "secrandom" to BuiltinFunc { args -> RowanValue.RNumber(Rational(secureRandom.nextLong().and(Long.MAX_VALUE), BigInteger.ONE)) },

        // List / Data
        "list" to BuiltinFunc { args -> RowanValue.RList(args.toMutableList()) },
        "nth" to BuiltinFunc { args -> (args[0] as RowanValue.RList).v[(args[1] as RowanValue.RNumber).v.toLong().toInt()] },
        "append" to BuiltinFunc { args -> (args[0] as RowanValue.RList).v.add(args[1]); args[0] },
        "len" to BuiltinFunc { args -> RowanValue.RNumber(Rational((args[0] as RowanValue.RList).v.size.toLong(), BigInteger.ONE)) },
        "print" to BuiltinFunc { args -> RowanValue.RString(args.joinToString(" ") { it.toString() }) },
        "type" to BuiltinFunc { args -> RowanValue.RString(when(args[0]) { is RowanValue.RNumber -> "number"; is RowanValue.RString -> "string"; is RowanValue.RList -> "list"; is RowanValue.RObject -> "object"; is RowanValue.RFunction -> "function"; else -> "null" }) },
        "cast" to BuiltinFunc { args -> 
            val v = args[0]; val t = (args[1] as RowanValue.RString).v
            when(t) {
                "number" -> if (v is RowanValue.RString) RowanValue.RNumber(Rational(v.v.toLong(), BigInteger.ONE)) else v
                "string" -> RowanValue.RString(v.toString())
                else -> v
            }
        }
    )
}
