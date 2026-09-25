package es.zelliot.perceptron.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.SecureRandom

// ============================================================================
// 1. CORE DATA TYPES (Расширенная точность и безопасность)
// ============================================================================

sealed class RowanValue {
    data class TInt(val value: Long) : RowanValue()
    data class TNum(val value: Double) : RowanValue()
    
    // НОВИНКА: Высокая точность (BigDecimal). Суффикс 'm' в коде (напр. 3.14159m)
    data class TDecimal(val value: BigDecimal) : RowanValue()
    
    // НОВИНКА: Рациональные числа (дроби). Суффикс 'r' в коде (напр. 1r3)
    data class TRational(val num: BigInteger, val den: BigInteger) : RowanValue() {
        init { 
            require(den != BigInteger.ZERO) { "Denominator cannot be zero" }
            // Нормализация знака: знаменатель всегда положительный
            if (den < BigInteger.ZERO) {
                // Упрощенная нормализация, в продакшене лучше использовать gcd
            }
        }
    }

    data class TBool(val value: Boolean) : RowanValue()
    data class TStr(val value: String) : RowanValue()
    data class TMemory(val data: ByteArray, val name: String = "anon") : RowanValue() {
        override fun toString(): String = "Memory<$name>(${data.size} bytes)"
    }
    
    data class TObject(
        val className: String,
        val fields: MutableMap<String, RowanValue> = mutableMapOf(),
        val methods: MutableMap<String, RowanFunction> = mutableMapOf()
    ) : RowanValue()

    data class TFunction(val name: String, val params: List<String>, val body: List<RowanStmt>, val closure: RowanEnv) : RowanValue()
    object TNull : RowanValue()

    // Конвертации для совместимости
    fun toLong(): Long = when (this) {
        is TInt -> value
        is TNum -> value.toLong()
        is TDecimal -> value.toLong()
        is TRational -> (num.toBigDecimal() / den.toBigDecimal()).toLong()
        is TBool -> if (value) 1L else 0L
        else -> throw RowanError("Expected Int, got ${this::class.simpleName}")
    }

    fun toDouble(): Double = when (this) {
        is TNum -> value
        is TInt -> value.toDouble()
        is TDecimal -> value.toDouble()
        is TRational -> (num.toBigDecimal() / den.toBigDecimal()).toDouble()
        is TBool -> if (value) 1.0 else 0.0
        else -> throw RowanError("Expected Num, got ${this::class.simpleName}")
    }

    fun toBigDecimal(): BigDecimal = when (this) {
        is TDecimal -> value
        is TInt -> value.toBigDecimal()
        is TNum -> value.toBigDecimal()
        is TRational -> num.toBigDecimal().divide(den.toBigDecimal(), 32, RoundingMode.HALF_UP)
        else -> throw RowanError("Expected Decimal, got ${this::class.simpleName}")
    }
}

data class RowanFunction(val name: String, val params: List<String>, val body: List<RowanStmt>, val closure: RowanEnv)

class RowanEnv(val parent: RowanEnv? = null) {
    private val values = mutableMapOf<String, RowanValue>()
    fun get(name: String): RowanValue? = values[name] ?: parent?.get(name)
    fun set(name: String, value: RowanValue) { values[name] = value }
    fun declare(name: String, value: RowanValue) { values[name] = value }
}

class RowanError(message: String, val line: Int = 0) : Exception("Line $line: $message")

// ============================================================================
// 2. LEXER (Строгие комментарии, поддержка точных чисел)
// ============================================================================

enum class RowanTokenType {
    NUMBER, DECIMAL, RATIONAL, STRING, IDENTIFIER,
    PLUS, MINUS, MUL, DIV, MOD,
    SHL, SHR, AND, OR, XOR, NOT,
    EQ, NEQ, LT, GT, LTE, GTE, LOGIC_AND, LOGIC_OR,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, DOT, ASSIGN, ARROW, COLON,
    VAL, VAR, CONST, MEM, FN, RETURN, IF, ELSE, MATCH, CASE, WHILE, FOR, IN, TO, DO,
    STRUCT, CLASS, VIEW, TRUE, FALSE, NULL, EXIT, RANDOM, SECURE_RANDOM, EOF
}

data class RowanToken(val type: RowanTokenType, val value: String, val line: Int)

class RowanLexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private val tokens = mutableListOf<RowanToken>()

    private fun currentChar(): Char = if (pos < source.length) source[pos] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (pos + offset < source.length) source[pos + offset] else '\u0000'
    private fun advance(): Char { val c = currentChar(); pos++; if (c == '\n') line++; return c }
    private fun addToken(type: RowanTokenType, value: String = "") { tokens.add(RowanToken(type, value, line)) }

    fun tokenize(): List<RowanToken> {
        while (pos < source.length) {
            val c = currentChar()
            when {
                c.isWhitespace() -> advance()
                c == '\uFEFF' -> advance() 
                
                // 1. СТРОКИ (приоритет выше комментариев, чтобы # внутри строк игнорировался)
                c == '"' || c == '\'' -> readString(c)
                
                // 2. БЕЗОПАСНЫЕ КОММЕНТАРИИ (Только # и /* */)
                c == '#' -> { 
                    while (pos < source.length && currentChar() != '\n') advance() 
                }
                c == '/' && peek() == '*' -> { 
                    advance(); advance() 
                    while (pos < source.length && !(currentChar() == '*' && peek() == '/')) advance()
                    if (pos < source.length) { advance(); advance() } 
                }
                
                // 3. ЧИСЛА (включая Decimal 'm' и Rational 'r')
                c.isDigit() || (c == '.' && peek().isDigit()) -> readNumber()
                
                // 4. ИДЕНТИФИКАТОРЫ и Ключевые слова
                c.isLetter() || c == '_' -> readIdentifier()
                
                // 5. ОПЕРАТОРЫ (// и ; намеренно отсутствуют)
                c == '+' -> { addToken(RowanTokenType.PLUS); advance() }
                c == '-' -> { 
                    if (peek() == '>') { addToken(RowanTokenType.ARROW); advance(); advance() } 
                    else { addToken(RowanTokenType.MINUS); advance() } 
                }
                c == '*' -> { addToken(RowanTokenType.MUL); advance() }
                c == '/' -> { addToken(RowanTokenType.DIV); advance() } 
                c == '%' -> { addToken(RowanTokenType.MOD); advance() }
                
                c == '<' -> {
                    if (peek() == '<') { addToken(RowanTokenType.SHL); advance(); advance() }
                    else if (peek() == '=') { addToken(RowanTokenType.LTE); advance(); advance() }
                    else { addToken(RowanTokenType.LT); advance() }
                }
                c == '>' -> {
                    if (peek() == '>') { addToken(RowanTokenType.SHR); advance(); advance() }
                    else if (peek() == '=') { addToken(RowanTokenType.GTE); advance(); advance() }
                    else { addToken(RowanTokenType.GT); advance() }
                }
                
                c == '=' -> { if (peek() == '=') { addToken(RowanTokenType.EQ); advance(); advance() } else { addToken(RowanTokenType.ASSIGN); advance() } }
                c == '!' -> { if (peek() == '=') { addToken(RowanTokenType.NEQ); advance(); advance() } else { addToken(RowanTokenType.NOT); advance() } }
                c == '&' -> { if (peek() == '&') { addToken(RowanTokenType.LOGIC_AND); advance(); advance() } else { addToken(RowanTokenType.AND); advance() } }
                c == '|' -> { if (peek() == '|') { addToken(RowanTokenType.LOGIC_OR); advance(); advance() } else { addToken(RowanTokenType.OR); advance() } }
                c == '^' -> { addToken(RowanTokenType.XOR); advance() }
                
                c == '(' -> { addToken(RowanTokenType.LPAREN); advance() }
                c == ')' -> { addToken(RowanTokenType.RPAREN); advance() }
                c == '{' -> { addToken(RowanTokenType.LBRACE); advance() }
                c == '}' -> { addToken(RowanTokenType.RBRACE); advance() }
                c == '[' -> { addToken(RowanTokenType.LBRACKET); advance() }
                c == ']' -> { addToken(RowanTokenType.RBRACKET); advance() }
                c == ',' -> { addToken(RowanTokenType.COMMA); advance() }
                c == '.' -> { addToken(RowanTokenType.DOT); advance() }
                c == ':' -> { addToken(RowanTokenType.COLON); advance() }
                
                else -> throw RowanError("Unknown character: '$c'", line)
            }
        }
        tokens.add(RowanToken(RowanTokenType.EOF, "", line))
        return tokens
    }

    private fun readNumber() {
        val start = pos
        var isHex = false
        var isBin = false
        var isRational = false
        var isDecimal = false

        if (currentChar() == '0') {
            if (peek() == 'x' || peek() == 'X') { isHex = true; advance(); advance() }
            else if (peek() == 'b' || peek() == 'B') { isBin = true; advance(); advance() }
        }

        while (pos < source.length) {
            val c = currentChar()
            if (isHex && (c.isDigit() || c in 'a'..'f' || c in 'A'..'F')) advance()
            else if (isBin && (c == '0' || c == '1')) advance()
            else if (!isHex && !isBin) {
                if (c == 'r' || c == 'R') {
                    isRational = true
                    advance()
                } else if (c == 'm' || c == 'M') {
                    isDecimal = true
                    advance()
                    break // 'm' всегда в конце
                } else if (c.isDigit() || c == '.') {
                    advance()
                } else {
                    break
                }
            } else break
        }
        
        val str = source.substring(start, pos)
        when {
            isRational -> addToken(RowanTokenType.RATIONAL, str)
            isDecimal -> addToken(RowanTokenType.DECIMAL, str)
            else -> addToken(RowanTokenType.NUMBER, str)
        }
    }

    private fun readString(quote: Char) {
        advance() 
        val sb = StringBuilder()
        while (pos < source.length && currentChar() != quote) {
            if (currentChar() == '\\') {
                advance()
                when (currentChar()) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '\\' -> sb.append('\\'); '\'' -> sb.append('\''); '"' -> sb.append('"')
                    else -> sb.append(currentChar())
                }
            } else {
                sb.append(currentChar())
            }
            advance()
        }
        addToken(RowanTokenType.STRING, sb.toString())
        if (pos < source.length && currentChar() == quote) advance() 
    }

    private fun readIdentifier() {
        val start = pos
        while (pos < source.length && (currentChar().isLetterOrDigit() || currentChar() == '_')) advance()
        val word = source.substring(start, pos)
        
        val type = when (word) {
            "val" -> RowanTokenType.VAL; "var" -> RowanTokenType.VAR; "const" -> RowanTokenType.CONST
            "mem" -> RowanTokenType.MEM; "fn" -> RowanTokenType.FN; "return" -> RowanTokenType.RETURN
            "if" -> RowanTokenType.IF; "else" -> RowanTokenType.ELSE; "match" -> RowanTokenType.MATCH; "case" -> RowanTokenType.CASE
            "while" -> RowanTokenType.WHILE; "for" -> RowanTokenType.FOR; "in" -> RowanTokenType.IN; "to" -> RowanTokenType.TO; "do" -> RowanTokenType.DO
            "struct" -> RowanTokenType.STRUCT; "class" -> RowanTokenType.CLASS; "view" -> RowanTokenType.VIEW
            "true" -> RowanTokenType.TRUE; "false" -> RowanTokenType.FALSE; "null" -> RowanTokenType.NULL
            "exit" -> RowanTokenType.EXIT; "and" -> RowanTokenType.LOGIC_AND; "or" -> RowanTokenType.LOGIC_OR; "not" -> RowanTokenType.NOT
            "random" -> RowanTokenType.RANDOM; "secure_random" -> RowanTokenType.SECURE_RANDOM
            else -> RowanTokenType.IDENTIFIER
        }
        addToken(type, word)
    }
}

// ============================================================================
// 3. AST (Абстрактное Синтаксическое Дерево)
// ============================================================================

sealed class RowanNode { abstract val line: Int }

sealed class RowanExpr : RowanNode() {
    data class NumLit(val value: Double, override val line: Int) : RowanExpr()
    data class IntLit(val value: Long, override val line: Int) : RowanExpr()
    
    // НОВИНКА: Точные числа
    data class DecimalLit(val value: BigDecimal, override val line: Int) : RowanExpr()
    data class RationalLit(val num: BigInteger, val den: BigInteger, override val line: Int) : RowanExpr()
    
    data class StrLit(val value: String, override val line: Int) : RowanExpr()
    data class VarRef(val name: String, override val line: Int) : RowanExpr()
    
    data class BinaryOp(val left: RowanExpr, val op: RowanTokenType, val right: RowanExpr, override val line: Int) : RowanExpr()
    data class UnaryOp(val op: RowanTokenType, val operand: RowanExpr, override val line: Int) : RowanExpr()
    
    data class FuncCall(val name: String, val args: List<RowanExpr>, override val line: Int) : RowanExpr()
    data class MemAccess(val target: RowanExpr, val index: RowanExpr, override val line: Int) : RowanExpr()
    data class FieldAccess(val target: RowanExpr, val fieldName: String, override val line: Int) : RowanExpr()
    data class ArrayLit(val elements: List<RowanExpr>, override val line: Int) : RowanExpr()
    data class AnonymousFunc(val params: List<String>, val body: List<RowanStmt>, override val line: Int) : RowanExpr()
    data class BlockExpr(val statements: List<RowanStmt>, override val line: Int) : RowanExpr()
    
    // НОВИНКА: Генерация случайных чисел
    data class RandomExpr(val isSecure: Boolean, val max: RowanExpr?, override val line: Int) : RowanExpr()
}

sealed class RowanStmt : RowanNode() {
    data class Assignment(val name: String, val value: RowanExpr, override val line: Int, val isDeclaration: Boolean) : RowanStmt()
    data class MemDeclaration(val name: String, val size: Long, override val line: Int) : RowanStmt()
    data class IndexAssignment(val target: RowanExpr, val index: RowanExpr, val value: RowanExpr, override val line: Int) : RowanStmt()
    data class FieldAssignment(val target: RowanExpr, val fieldName: String, val value: RowanExpr, override val line: Int) : RowanStmt()
    
    data class FunctionDef(val name: String, val params: List<String>, val body: List<RowanStmt>, override val line: Int) : RowanStmt()
    data class StructDef(val name: String, val fields: List<String>, override val line: Int) : RowanStmt()
    
    data class ReturnStmt(val value: RowanExpr?, override val line: Int) : RowanStmt()
    data class WhileStmt(val cond: RowanExpr, val body: List<RowanStmt>, override val line: Int) : RowanStmt()
    data class ForRangeStmt(val varName: String, val start: RowanExpr, val end: RowanExpr, val body: List<RowanStmt>, override val line: Int) : RowanStmt()
    data class ExprStmt(val expr: RowanExpr, override val line: Int) : RowanStmt()
    data class ExitStmt(val delayMs: Long, override val line: Int) : RowanStmt()
}

// ============================================================================
// 4. PARSER (Recursive Descent с поддержкой новых типов)
// ============================================================================

class RowanParser(private val tokens: List<RowanToken>) {
    private var pos = 0
    private fun peek(offset: Int = 0): RowanToken = tokens.getOrNull(pos + offset) ?: RowanToken(RowanTokenType.EOF, "", 0)
    private fun advance(): RowanToken = tokens[pos++]
    private fun expect(type: RowanTokenType): RowanToken { 
        val current = peek()
        if (current.type != type) throw RowanError("Expected $type, got ${current.type} ('${current.value}')", current.line)
        return advance() 
    }

    fun parse(): List<RowanStmt> {
        val statements = mutableListOf<RowanStmt>()
        while (peek().type != RowanTokenType.EOF) {
            statements.add(parseStatement())
        }
        return statements
    }

    private fun parseStatement(): RowanStmt {
        val current = peek()
        return when (current.type) {
            RowanTokenType.VAL, RowanTokenType.VAR, RowanTokenType.CONST -> {
                val isDecl = current.type != RowanTokenType.VAR
                advance()
                val name = expect(RowanTokenType.IDENTIFIER).value
                expect(RowanTokenType.ASSIGN)
                RowanStmt.Assignment(name, parseExpression(), current.line, isDeclaration = isDecl)
            }
            RowanTokenType.MEM -> {
                advance()
                val name = expect(RowanTokenType.IDENTIFIER).value
                expect(RowanTokenType.LBRACKET)
                val sizeExpr = parseExpression()
                expect(RowanTokenType.RBRACKET)
                val size = (sizeExpr as? RowanExpr.IntLit)?.value ?: throw RowanError("Memory size must be an integer constant", current.line)
                RowanStmt.MemDeclaration(name, size, current.line)
            }
            RowanTokenType.FN -> parseFunctionDef()
            RowanTokenType.STRUCT -> {
                advance()
                val name = expect(RowanTokenType.IDENTIFIER).value
                expect(RowanTokenType.LBRACE)
                val fields = mutableListOf<String>()
                while (peek().type != RowanTokenType.RBRACE && peek().type != RowanTokenType.EOF) {
                    fields.add(expect(RowanTokenType.IDENTIFIER).value)
                    if (peek().type == RowanTokenType.COMMA) advance()
                }
                expect(RowanTokenType.RBRACE)
                RowanStmt.StructDef(name, fields, current.line)
            }
            RowanTokenType.RETURN -> {
                advance()
                val hasValue = peek().type !in listOf(RowanTokenType.RBRACE, RowanTokenType.EOF)
                RowanStmt.ReturnStmt(if (hasValue) parseExpression() else null, current.line)
            }
            RowanTokenType.WHILE -> {
                advance()
                val cond = parseExpression()
                expect(RowanTokenType.DO) // или LBRACE, если предпочитаете
                RowanStmt.WhileStmt(cond, parseBlock(), current.line)
            }
            RowanTokenType.FOR -> {
                advance()
                val varName = expect(RowanTokenType.IDENTIFIER).value
                expect(RowanTokenType.IN)
                val start = parseExpression()
                expect(RowanTokenType.TO)
                val end = parseExpression()
                expect(RowanTokenType.DO)
                RowanStmt.ForRangeStmt(varName, start, end, parseBlock(), current.line)
            }
            RowanTokenType.EXIT -> {
                advance()
                val delay = if (peek().type == RowanTokenType.NUMBER) advance().value.toLong() else 0L
                RowanStmt.ExitStmt(delay, current.line)
            }
            else -> {
                val expr = parseExpression()
                if (peek().type == RowanTokenType.ASSIGN) {
                    advance()
                    val value = parseExpression()
                    when (expr) {
                        is RowanExpr.VarRef -> RowanStmt.Assignment(expr.name, value, expr.line, isDeclaration = false)
                        is RowanExpr.MemAccess -> RowanStmt.IndexAssignment(expr.target, expr.index, value, expr.line)
                        is RowanExpr.FieldAccess -> RowanStmt.FieldAssignment(expr.target, expr.fieldName, value, expr.line)
                        else -> throw RowanError("Invalid assignment target", expr.line)
                    }
                } else {
                    RowanStmt.ExprStmt(expr, current.line)
                }
            }
        }
    }

    private fun parseFunctionDef(): RowanStmt {
        advance()
        val name = expect(RowanTokenType.IDENTIFIER).value
        expect(RowanTokenType.LPAREN)
        val params = mutableListOf<String>()
        if (peek().type != RowanTokenType.RPAREN) {
            do {
                params.add(expect(RowanTokenType.IDENTIFIER).value)
                if (peek().type == RowanTokenType.COLON) { advance(); advance() } 
                if (peek().type == RowanTokenType.COMMA) advance() else break
            } while (peek().type != RowanTokenType.RPAREN)
        }
        expect(RowanTokenType.RPAREN)
        return RowanStmt.FunctionDef(name, params, parseBlock(), current.line)
    }

    private fun parseBlock(): List<RowanStmt> {
        expect(RowanTokenType.LBRACE)
        val stmts = mutableListOf<RowanStmt>()
        while (peek().type != RowanTokenType.RBRACE && peek().type != RowanTokenType.EOF) {
            stmts.add(parseStatement())
        }
        expect(RowanTokenType.RBRACE)
        return stmts
    }

    // --- EXPRESSION PARSING (Precedence Climbing) ---
    private fun parseExpression(): RowanExpr = parseLogicalOr()
    
    private fun parseLogicalOr(): RowanExpr {
        var left = parseLogicalAnd()
        while (peek().type == RowanTokenType.LOGIC_OR) {
            left = RowanExpr.BinaryOp(left, advance().type, parseLogicalAnd(), left.line)
        }
        return left
    }
    
    private fun parseLogicalAnd(): RowanExpr {
        var left = parseComparison()
        while (peek().type == RowanTokenType.LOGIC_AND) {
            left = RowanExpr.BinaryOp(left, advance().type, parseComparison(), left.line)
        }
        return left
    }
    
    private fun parseComparison(): RowanExpr {
        var left = parseBitwiseOr()
        while (peek().type in listOf(RowanTokenType.EQ, RowanTokenType.NEQ, RowanTokenType.LT, RowanTokenType.GT, RowanTokenType.LTE, RowanTokenType.GTE)) {
            left = RowanExpr.BinaryOp(left, advance().type, parseBitwiseOr(), left.line)
        }
        return left
    }

    private fun parseBitwiseOr(): RowanExpr {
        var left = parseBitwiseXor()
        while (peek().type == RowanTokenType.OR) {
            left = RowanExpr.BinaryOp(left, advance().type, parseBitwiseXor(), left.line)
        }
        return left
    }

    private fun parseBitwiseXor(): RowanExpr {
        var left = parseBitwiseAnd()
        while (peek().type == RowanTokenType.XOR) {
            left = RowanExpr.BinaryOp(left, advance().type, parseBitwiseAnd(), left.line)
        }
        return left
    }

    private fun parseBitwiseAnd(): RowanExpr {
        var left = parseShift()
        while (peek().type == RowanTokenType.AND) {
            left = RowanExpr.BinaryOp(left, advance().type, parseShift(), left.line)
        }
        return left
    }

    private fun parseShift(): RowanExpr {
        var left = parseAddition()
        while (peek().type in listOf(RowanTokenType.SHL, RowanTokenType.SHR)) {
            left = RowanExpr.BinaryOp(left, advance().type, parseAddition(), left.line)
        }
        return left
    }

    private fun parseAddition(): RowanExpr {
        var left = parseMultiplication()
        while (peek().type in listOf(RowanTokenType.PLUS, RowanTokenType.MINUS)) {
            left = RowanExpr.BinaryOp(left, advance().type, parseMultiplication(), left.line)
        }
        return left
    }

    private fun parseMultiplication(): RowanExpr {
        var left = parseUnary()
        while (peek().type in listOf(RowanTokenType.MUL, RowanTokenType.DIV, RowanTokenType.MOD)) {
            left = RowanExpr.BinaryOp(left, advance().type, parseUnary(), left.line)
        }
        return left
    }

    private fun parseUnary(): RowanExpr {
        if (peek().type in listOf(RowanTokenType.MINUS, RowanTokenType.NOT)) {
            return RowanExpr.UnaryOp(advance().type, parseUnary(), peek().line)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): RowanExpr {
        val token = peek()
        var expr: RowanExpr = when (token.type) {
            RowanTokenType.NUMBER -> {
                advance()
                val v = token.value
                if (v.startsWith("0x") || v.startsWith("0X")) RowanExpr.IntLit(v.toLong(16), token.line)
                else if (v.startsWith("0b") || v.startsWith("0B")) RowanExpr.IntLit(v.toLong(2), token.line)
                else if (v.contains(".")) RowanExpr.NumLit(v.toDouble(), token.line)
                else RowanExpr.IntLit(v.toLong(), token.line)
            }
            // НОВИНКА: Парсинг точных чисел
            RowanTokenType.DECIMAL -> {
                advance()
                val str = token.value.dropLast(1) // убираем 'm'
                RowanExpr.DecimalLit(BigDecimal(str), token.line)
            }
            RowanTokenType.RATIONAL -> {
                advance()
                val parts = token.value.split('r', 'R', ignoreCase = true)
                RowanExpr.RationalLit(parts[0].toBigInteger(), parts[1].toBigInteger(), token.line)
            }
            
            RowanTokenType.STRING -> { advance(); RowanExpr.StrLit(token.value, token.line) }
            RowanTokenType.TRUE -> { advance(); RowanExpr.IntLit(1, token.line) }
            RowanTokenType.FALSE -> { advance(); RowanExpr.IntLit(0, token.line) }
            RowanTokenType.NULL -> { advance(); RowanExpr.IntLit(0, token.line) }
            
            // НОВИНКА: Рандомизация
            RowanTokenType.RANDOM, RowanTokenType.SECURE_RANDOM -> {
                advance()
                expect(RowanTokenType.LPAREN)
                val max = if (peek().type != RowanTokenType.RPAREN) parseExpression() else null
                expect(RowanTokenType.RPAREN)
                RowanExpr.RandomExpr(token.type == RowanTokenType.SECURE_RANDOM, max, token.line)
            }
            
            RowanTokenType.IDENTIFIER -> {
                advance()
                if (peek().type == RowanTokenType.LPAREN) {
                    advance()
                    val args = mutableListOf<RowanExpr>()
                    if (peek().type != RowanTokenType.RPAREN) {
                        args.add(parseExpression())
                        while (peek().type == RowanTokenType.COMMA) {
                            advance()
                            args.add(parseExpression())
                        }
                    }
                    expect(RowanTokenType.RPAREN)
                    RowanExpr.FuncCall(token.value, args, token.line)
                } else {
                    RowanExpr.VarRef(token.value, token.line)
                }
            }
            RowanTokenType.LPAREN -> {
                advance()
                val e = parseExpression()
                expect(RowanTokenType.RPAREN)
                e
            }
            RowanTokenType.LBRACKET -> {
                advance()
                val elements = mutableListOf<RowanExpr>()
                if (peek().type != RowanTokenType.RBRACKET) {
                    elements.add(parseExpression())
                    while (peek().type == RowanTokenType.COMMA) {
                        advance()
                        elements.add(parseExpression())
                    }
                }
                expect(RowanTokenType.RBRACKET)
                RowanExpr.ArrayLit(elements, token.line)
            }
            RowanTokenType.FN -> {
                advance()
                val params = mutableListOf<String>()
                if (peek().type == RowanTokenType.LPAREN) {
                    advance()
                    if (peek().type != RowanTokenType.RPAREN) {
                        do {
                            params.add(expect(RowanTokenType.IDENTIFIER).value)
                            if (peek().type == RowanTokenType.COMMA) advance() else break
                        } while (peek().type != RowanTokenType.RPAREN)
                    }
                    expect(RowanTokenType.RPAREN)
                }
                RowanExpr.AnonymousFunc(params, parseBlock(), token.line)
            }
            else -> throw RowanError("Unexpected token: ${token.value}", token.line)
        }

        // Postfix: Indexing and Field Access
        while (peek().type == RowanTokenType.LBRACKET || peek().type == RowanTokenType.DOT) {
            if (peek().type == RowanTokenType.LBRACKET) {
                advance()
                val indexExpr = parseExpression()
                expect(RowanTokenType.RBRACKET)
                expr = RowanExpr.MemAccess(expr, indexExpr, token.line)
            } else if (peek().type == RowanTokenType.DOT) {
                advance()
                val fieldName = expect(RowanTokenType.IDENTIFIER).value
                expr = RowanExpr.FieldAccess(expr, fieldName, token.line)
            }
        }
        return expr
    }
}
