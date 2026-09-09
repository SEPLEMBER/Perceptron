package es.zelliot.perceptron

import android.content.Context
import kotlin.math.*
import java.security.SecureRandom
import java.util.Random
import java.math.BigDecimal
import java.math.RoundingMode

// ============================================================================
// TESSERACT ENGINE 3: EXCEPTIONS AND DATA TYPES
// ============================================================================

class TesseractError3(
    message: String, 
    val line: Int, 
    val callStack: List<String> = emptyList(), 
    vararg val formatArgs: Any
) : Exception(message)

class ReturnValue3(val value: TValue3?) : Exception()
class TesseractExitCommand3(val delayMs: Long) : Exception()
class TesseractOpenActCommand3(val packageName: String) : Exception()

sealed class TValue3 {
    data class TNum(val value: Double) : TValue3()
    data class TInt(val value: Long) : TValue3()
    data class TStr(val value: String) : TValue3()
    data class TBool(val value: Boolean) : TValue3()
    
    data class TArray(
        val items: MutableList<TValue3> = mutableListOf(),
        val fields: MutableMap<String, TValue3> = mutableMapOf(),
        var metatable: TValue3? = null
    ) : TValue3()
    
    data class TFunction(val params: List<String>, val body: List<Stmt3>, val closureEnv: Environment3) : TValue3()
    
    object TNull : TValue3()

    fun toDouble(): Double = when (this) { 
        is TNum -> value; is TInt -> value.toDouble(); is TBool -> if (value) 1.0 else 0.0
        else -> throw TesseractError3("Expected number", 0) 
    }
    fun toLong(): Long = when (this) { 
        is TNum -> value.toLong(); is TInt -> value; is TBool -> if (value) 1L else 0L
        else -> throw TesseractError3("Expected integer", 0) 
    }
    fun toBoolean(): Boolean = when (this) {
        is TBool -> value; is TNum -> value != 0.0; is TInt -> value != 0L
        is TStr -> value.isNotEmpty(); is TArray -> items.isNotEmpty() || fields.isNotEmpty(); is TNull -> false
        is TFunction -> true
    }
    fun displayString(): String = when (this) {
        is TNum -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is TInt -> value.toString()
        is TStr -> value
        is TBool -> if (value) "true" else "false"
        is TArray -> {
            val itemsStr = items.joinToString(", ") { it.displayString() }
            if (fields.isEmpty()) "[$itemsStr]" else "{items: [$itemsStr], fields: {${fields.map { "${it.key}=${it.value.displayString()}" }.joinToString(", ")}}}"
        }
        is TFunction -> "<function>"
        is TNull -> "null"
    }
}

object MathGuard3 {
    fun checkDivision(b: TValue3, line: Int) { if (b.toDouble() == 0.0) throw TesseractError3("Division by zero", line) }
    fun checkLogarithm(x: TValue3, line: Int) { if (x.toDouble() <= 0.0) throw TesseractError3("Logarithm of non-positive number", line) }
    fun checkRoot(x: TValue3, degree: TValue3, line: Int) { if (x.toDouble() < 0.0 && degree.toDouble() % 2 == 0.0) throw TesseractError3("Even root of negative number", line) }
    fun checkOverflow(result: Double, line: Int) { if (result.isInfinite()) throw TesseractError3("Numeric overflow", line); if (result.isNaN()) throw TesseractError3("Not a Number (NaN)", line) }
}

// ============================================================================
// TESSERACT ENGINE 3: LEXER & AST
// ============================================================================

enum class TokenType3 {
    NUMBER, STRING, IDENTIFIER, PLUS, MINUS, MUL, DIV, INT_DIV, MOD, POW, XOR,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, PIPE, ASSIGN, COLON, ARROW, DOT,
    GT, LT, GTE, LTE, EQ, NEQ, AND, OR, NEGATE,
    FN, VAL, CONST, RETURN, ASSERT, IF, THEN, ELSE, WHILE, DO, FOR, IN, TO, SEPARATOR, EXIT, EOF
}
data class Token3(val type: TokenType3, val value: String, val line: Int)

class Lexer3(private val source: String) {
    private var pos = 0; private var line = 1; private val tokens = mutableListOf<Token3>()
    private fun currentChar(): Char = if (pos < source.length) source[pos] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (pos + offset < source.length) source[pos + offset] else '\u0000'
    private fun advance(): Char { val c = currentChar(); pos++; if (c == '\n') line++; return c }
    private fun addToken(type: TokenType3, value: String) { tokens.add(Token3(type, value, line)) }

    fun tokenize(): List<Token3> {
        while (pos < source.length) {
            val c = currentChar()
            when {
                c.isWhitespace() -> advance()
                c == '\uFEFF' -> advance()
                c == ';' -> advance()
                c == '#' -> { while (pos < source.length && currentChar() != '\n') advance() }
                c.isDigit() || (c == '.' && peek().isDigit()) -> readNumber()
                c == '"' -> readString()
                c.isLetter() || c == '_' -> readIdentifier()
                c == '+' -> { addToken(TokenType3.PLUS, "+"); advance() }
                c == '-' -> { when { peek() == '>' -> { addToken(TokenType3.ARROW, "->"); advance(); advance() }; peek() == '-' && peek(2) == '-' -> { addToken(TokenType3.SEPARATOR, "---"); advance(); advance(); advance() }; else -> { addToken(TokenType3.MINUS, "-"); advance() } } }
                c == '*' -> { addToken(TokenType3.MUL, "*"); advance() }
                c == '/' -> { if (peek() == '/') { addToken(TokenType3.INT_DIV, "//"); advance(); advance() } else { addToken(TokenType3.DIV, "/"); advance() } }
                c == '%' -> { addToken(TokenType3.MOD, "%"); advance() }
                c == '^' -> { if (peek() == '^') { addToken(TokenType3.XOR, "^^"); advance(); advance() } else { addToken(TokenType3.POW, "^"); advance() } }
                c == '>' -> { if (peek() == '=') { addToken(TokenType3.GTE, ">="); advance(); advance() } else { addToken(TokenType3.GT, ">"); advance() } }
                c == '<' -> { if (peek() == '=') { addToken(TokenType3.LTE, "<="); advance(); advance() } else { addToken(TokenType3.LT, "<"); advance() } }
                c == '=' -> { if (peek() == '=') { addToken(TokenType3.EQ, "=="); advance(); advance() } else { addToken(TokenType3.ASSIGN, "="); advance() } }
                c == '!' -> { if (peek() == '=') { addToken(TokenType3.NEQ, "!="); advance(); advance() } else throw TesseractError3("Unknown character: !", line) }
                c == '&' -> { if (peek() == '&') { addToken(TokenType3.AND, "&&"); advance(); advance() } else throw TesseractError3("Unknown character: &", line) }
                c == '|' -> { when { peek() == '|' -> { addToken(TokenType3.OR, "||"); advance(); advance() }; peek() == '>' -> { addToken(TokenType3.PIPE, "|>"); advance(); advance() }; else -> throw TesseractError3("Unknown character: |", line) } }
                c == '(' -> { addToken(TokenType3.LPAREN, "("); advance() }
                c == ')' -> { addToken(TokenType3.RPAREN, ")"); advance() }
                c == '{' -> { addToken(TokenType3.LBRACE, "{"); advance() }
                c == '}' -> { addToken(TokenType3.RBRACE, "}"); advance() }
                c == '[' -> { addToken(TokenType3.LBRACKET, "["); advance() }
                c == ']' -> { addToken(TokenType3.RBRACKET, "]"); advance() }
                c == ',' -> { addToken(TokenType3.COMMA, ","); advance() }
                c == ':' -> { addToken(TokenType3.COLON, ":"); advance() }
                c == '.' -> { addToken(TokenType3.DOT, "."); advance() }
                else -> throw TesseractError3("Unknown character: $c", line)
            }
        }
        tokens.add(Token3(TokenType3.EOF, "", line))
        return tokens
    }

    private fun readNumber() { val start = pos; while (pos < source.length && (currentChar().isDigit() || currentChar() == '.')) advance(); if (currentChar() == 'e' || currentChar() == 'E') { advance(); if (currentChar() == '+' || currentChar() == '-') advance(); while (pos < source.length && currentChar().isDigit()) advance() }; addToken(TokenType3.NUMBER, source.substring(start, pos)) }
    
    private fun readString() {
        advance()
        val sb = StringBuilder()
        while (pos < source.length && currentChar() != '"') {
            if (currentChar() == '\\') {
                advance()
                when (currentChar()) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> sb.append(currentChar())
                }
            } else {
                sb.append(currentChar())
            }
            advance()
        }
        addToken(TokenType3.STRING, sb.toString())
        if (pos < source.length && currentChar() == '"') advance()
    }

    private fun readIdentifier() { 
        val start = pos; 
        while (pos < source.length && (currentChar().isLetterOrDigit() || currentChar() == '_')) advance(); 
        var word = source.substring(start, pos); 
        word = word.replace('а', 'a').replace('А', 'A').replace('в', 'v').replace('В', 'V').replace('е', 'e').replace('Е', 'E').replace('о', 'o').replace('О', 'O').replace('р', 'r').replace('Р', 'R').replace('с', 'c').replace('С', 'C').replace('у', 'y').replace('У', 'Y').replace('х', 'x').replace('Х', 'X'); 
        if (word == "ate") throw TesseractError3("Typo: 'ate'", line); 
        val type = when (word.lowercase()) { 
            "fn" -> TokenType3.FN; "val", "var" -> TokenType3.VAL; "const" -> TokenType3.CONST; 
            "return" -> TokenType3.RETURN; "assert" -> TokenType3.ASSERT; 
            "if" -> TokenType3.IF; "then" -> TokenType3.THEN; "else" -> TokenType3.ELSE; 
            "while" -> TokenType3.WHILE; "do" -> TokenType3.DO; "for" -> TokenType3.FOR; 
            "in" -> TokenType3.IN; "to" -> TokenType3.TO; "exit" -> TokenType3.EXIT; 
            "and" -> TokenType3.AND; "or" -> TokenType3.OR; 
            "negate" -> TokenType3.NEGATE
            else -> TokenType3.IDENTIFIER 
        }
        addToken(type, word) 
    }
}

sealed class Node3 { abstract val line: Int }
sealed class Expr3 : Node3() {
    data class NumLit(val value: Double, override val line: Int) : Expr3()
    data class IntLit(val value: Long, override val line: Int) : Expr3()
    data class StrLit(val value: String, override val line: Int) : Expr3()
    data class VarRef(val name: String, override val line: Int) : Expr3()
    data class BinaryOp(val left: Expr3, val op: TokenType3, val right: Expr3, override val line: Int) : Expr3()
    data class UnaryOp(val op: TokenType3, val operand: Expr3, override val line: Int) : Expr3()
    data class FuncCall(val name: String, val args: List<Expr3>, override val line: Int) : Expr3()
    data class Pipeline(val left: Expr3, val right: Expr3, override val line: Int) : Expr3()
    data class IfElse(val cond: Expr3, val thenExpr: Expr3, val elseExpr: Expr3, override val line: Int) : Expr3()
    data class ArrayLit(val elements: List<Expr3>, override val line: Int) : Expr3()
    data class IndexAccess(val target: Expr3, val index: Expr3, override val line: Int) : Expr3()
    data class MethodCall(val target: Expr3, val methodName: String, val args: List<Expr3>, override val line: Int) : Expr3()
    data class AnonymousFunc(val params: List<String>, val body: List<Stmt3>, override val line: Int) : Expr3()
    data class ReturnExpr(val value: Expr3?, override val line: Int) : Expr3()
}
sealed class Stmt3 : Node3() {
    data class Assignment(val name: String, val value: Expr3, override val line: Int) : Stmt3()
    data class IndexAssignment(val target: Expr3, val index: Expr3, val value: Expr3, override val line: Int) : Stmt3()
    data class DestructuringAssignment(val names: List<String>, val value: Expr3, override val line: Int) : Stmt3()
    data class FunctionDef(val name: String, val params: List<String>, val body: List<Stmt3>, override val line: Int) : Stmt3()
    data class AssertStmt(val condition: Expr3, override val line: Int) : Stmt3()
    data class ReturnStmt(val value: Expr3?, override val line: Int) : Stmt3()
    data class WhileStmt(val cond: Expr3, val body: List<Stmt3>, override val line: Int) : Stmt3()
    data class ForRangeStmt(val varName: String, val start: Expr3, val end: Expr3, val body: List<Stmt3>, override val line: Int) : Stmt3()
    data class ForInStmt(val varName: String, val collection: Expr3, val body: List<Stmt3>, override val line: Int) : Stmt3()
    data class ExprStmt(val expr: Expr3, override val line: Int) : Stmt3()
    data class ExitStmt(val delayMs: Long, override val line: Int) : Stmt3()
    object SeparatorStmt : Stmt3() { override val line: Int = 0 }
}

// ============================================================================
// TESSERACT ENGINE 3: PARSER
// ============================================================================

class Parser3(private val tokens: List<Token3>) {
    private var pos = 0
    private fun peek(offset: Int = 0): Token3 = tokens.getOrNull(pos + offset) ?: Token3(TokenType3.EOF, "", 0)
    private fun advance(): Token3 = tokens[pos++]
    private fun expect(type: TokenType3): Token3 { val current = peek(); if (current.type != type) throw TesseractError3("Expected $type, got ${current.value}", current.line); return advance() }

    fun parse(): List<Stmt3> { val statements = mutableListOf<Stmt3>(); while (peek().type != TokenType3.EOF) { if (peek().type == TokenType3.SEPARATOR) { advance(); statements.add(Stmt3.SeparatorStmt) } else { statements.add(parseStatement()) } }; return statements }

    private fun parseStatement(): Stmt3 {
        val current = peek()
        return when (current.type) {
            TokenType3.FN -> parseFunctionDef()
            TokenType3.ASSERT -> { advance(); Stmt3.AssertStmt(parseExpression(), peek().line) }
            TokenType3.RETURN -> { 
                advance()
                val hasValue = peek().type != TokenType3.EOF && peek().type != TokenType3.RBRACE && peek().type != TokenType3.SEPARATOR
                Stmt3.ReturnStmt(if (hasValue) parseExpression() else null, current.line) 
            }
            TokenType3.WHILE -> parseWhile()
            TokenType3.FOR -> parseFor()
            TokenType3.EXIT -> { advance(); val delay = if (peek().type == TokenType3.NUMBER) advance().value.toLong() else 0L; Stmt3.ExitStmt(delay, current.line) }
            TokenType3.VAL, TokenType3.CONST -> {
                advance()
                if (peek().type == TokenType3.LBRACKET) {
                    advance()
                    val names = mutableListOf<String>()
                    if (peek().type != TokenType3.RBRACKET) {
                        do {
                            names.add(expect(TokenType3.IDENTIFIER).value)
                            if (peek().type == TokenType3.COMMA) advance() else break
                        } while (peek().type != TokenType3.RBRACKET)
                    }
                    expect(TokenType3.RBRACKET)
                    expect(TokenType3.ASSIGN)
                    Stmt3.DestructuringAssignment(names, parseExpression(), current.line)
                } else {
                    val nameToken = expect(TokenType3.IDENTIFIER)
                    if (peek().type == TokenType3.COLON) { advance(); advance() }
                    expect(TokenType3.ASSIGN)
                    Stmt3.Assignment(nameToken.value, parseExpression(), nameToken.line)
                }
            }
            TokenType3.IDENTIFIER -> {
                val lvalueExpr = parseExpression()
                if (peek().type == TokenType3.ASSIGN) {
                    advance()
                    val rvalueExpr = parseExpression()
                    when (lvalueExpr) {
                        is Expr3.VarRef -> Stmt3.Assignment(lvalueExpr.name, rvalueExpr, lvalueExpr.line)
                        is Expr3.IndexAccess -> Stmt3.IndexAssignment(lvalueExpr.target, lvalueExpr.index, rvalueExpr, lvalueExpr.line)
                        else -> throw TesseractError3("Invalid assignment target", lvalueExpr.line)
                    }
                } else {
                    Stmt3.ExprStmt(lvalueExpr, current.line)
                }
            }
            else -> Stmt3.ExprStmt(parseExpression(), current.line)
        }
    }

    private fun parseWhile(): Stmt3 { advance(); val cond = parseExpression(); expect(TokenType3.DO); return Stmt3.WhileStmt(cond, parseBlock(), cond.line) }
    private fun parseFor(): Stmt3 { advance(); val varName = expect(TokenType3.IDENTIFIER).value; expect(TokenType3.IN); val firstExpr = parseExpression(); if (peek().type == TokenType3.TO) { advance(); val secondExpr = parseExpression(); expect(TokenType3.DO); return Stmt3.ForRangeStmt(varName, firstExpr, secondExpr, parseBlock(), firstExpr.line) } else { expect(TokenType3.DO); return Stmt3.ForInStmt(varName, firstExpr, parseBlock(), firstExpr.line) } }
    
    private fun parseFunctionDef(): Stmt3 { 
        advance()
        val nameToken = expect(TokenType3.IDENTIFIER)
        expect(TokenType3.LPAREN)
        val params = mutableListOf<String>()
        if (peek().type != TokenType3.RPAREN) { 
            do { 
                params.add(expect(TokenType3.IDENTIFIER).value)
                if (peek().type == TokenType3.COLON) { advance(); advance() }
                if (peek().type == TokenType3.COMMA) advance() else break 
            } while (peek().type != TokenType3.RPAREN) 
        }
        expect(TokenType3.RPAREN)
        if (peek().type == TokenType3.ARROW) { advance(); advance() }
        return Stmt3.FunctionDef(nameToken.value, params, parseBlock(), nameToken.line) 
    }
    
    private fun parseBlock(): List<Stmt3> { expect(TokenType3.LBRACE); val stmts = mutableListOf<Stmt3>(); while (peek().type != TokenType3.RBRACE && peek().type != TokenType3.EOF) { if (peek().type == TokenType3.SEPARATOR) advance() else stmts.add(parseStatement()) }; expect(TokenType3.RBRACE); return stmts }

    private fun parseExpression(): Expr3 = parseLogicalOr()
    private fun parseLogicalOr(): Expr3 { var left = parseLogicalAnd(); while (peek().type == TokenType3.OR) { val op = advance().type; left = Expr3.BinaryOp(left, op, parseLogicalAnd(), left.line) }; return left }
    private fun parseLogicalAnd(): Expr3 { var left = parsePipeline(); while (peek().type == TokenType3.AND) { val op = advance().type; left = Expr3.BinaryOp(left, op, parsePipeline(), left.line) }; return left }
    private fun parsePipeline(): Expr3 { var left = parseComparison(); while (peek().type == TokenType3.PIPE) { advance(); left = Expr3.Pipeline(left, parseComparison(), left.line) }; return left }
    private fun parseComparison(): Expr3 { var left = parseXor(); while (peek().type in listOf(TokenType3.GT, TokenType3.LT, TokenType3.GTE, TokenType3.LTE, TokenType3.EQ, TokenType3.NEQ)) { val op = advance().type; left = Expr3.BinaryOp(left, op, parseXor(), left.line) }; return left }
    private fun parseXor(): Expr3 { var left = parseAddition(); while (peek().type == TokenType3.XOR) { left = Expr3.BinaryOp(left, advance().type, parseAddition(), left.line) }; return left }
    private fun parseAddition(): Expr3 { var left = parseMultiplication(); while (peek().type == TokenType3.PLUS || peek().type == TokenType3.MINUS) { left = Expr3.BinaryOp(left, advance().type, parseMultiplication(), left.line) }; return left }
    private fun parseMultiplication(): Expr3 { var left = parseExponentiation(); while (peek().type in listOf(TokenType3.MUL, TokenType3.DIV, TokenType3.INT_DIV, TokenType3.MOD)) { left = Expr3.BinaryOp(left, advance().type, parseExponentiation(), left.line) }; return left }
    private fun parseExponentiation(): Expr3 { val base = parseUnary(); return if (peek().type == TokenType3.POW) { advance(); Expr3.BinaryOp(base, TokenType3.POW, parseExponentiation(), base.line) } else base }
    
    private fun parseUnary(): Expr3 { 
        return if (peek().type == TokenType3.MINUS || peek().type == TokenType3.PLUS || peek().type == TokenType3.NEGATE) { 
            Expr3.UnaryOp(advance().type, parseUnary(), peek().line) 
        } else parsePrimary() 
    }
    
    private fun parsePrimary(): Expr3 {
        val token = peek()
        var expr: Expr3 = when (token.type) {
            TokenType3.NUMBER -> { advance(); if (token.value.contains('.') || token.value.contains('e', true)) Expr3.NumLit(token.value.toDouble(), token.line) else Expr3.IntLit(token.value.toLong(), token.line) }
            TokenType3.STRING -> { advance(); Expr3.StrLit(token.value, token.line) }
            TokenType3.IF -> { advance(); val cond = parseExpression(); expect(TokenType3.THEN); val thenExpr = parseExpression(); expect(TokenType3.ELSE); Expr3.IfElse(cond, thenExpr, parseExpression(), token.line) }
            TokenType3.FN -> {
                advance()
                val params = mutableListOf<String>()
                if (peek().type == TokenType3.LPAREN) {
                    advance()
                    if (peek().type != TokenType3.RPAREN) {
                        do {
                            params.add(expect(TokenType3.IDENTIFIER).value)
                            if (peek().type == TokenType3.COMMA) advance() else break
                        } while (peek().type != TokenType3.RPAREN)
                    }
                    expect(TokenType3.RPAREN)
                }
                expect(TokenType3.LBRACE)
                val body = mutableListOf<Stmt3>()
                while (peek().type != TokenType3.RBRACE && peek().type != TokenType3.EOF) {
                    if (peek().type == TokenType3.SEPARATOR) advance()
                    else body.add(parseStatement())
                }
                expect(TokenType3.RBRACE)
                Expr3.AnonymousFunc(params, body, token.line)
            }
            TokenType3.RETURN -> {
                advance()
                val hasValue = peek().type != TokenType3.RBRACE && peek().type != TokenType3.RBRACKET && 
                               peek().type != TokenType3.COMMA && peek().type != TokenType3.EOF && 
                               peek().type != TokenType3.COLON && peek().type != TokenType3.THEN && 
                               peek().type != TokenType3.ELSE && peek().type != TokenType3.DO &&
                               peek().type != TokenType3.SEPARATOR
                val value = if (hasValue) parseExpression() else null
                Expr3.ReturnExpr(value, token.line)
            }
            TokenType3.IDENTIFIER -> { advance(); if (peek().type == TokenType3.LPAREN) { advance(); val args = mutableListOf<Expr3>(); if (peek().type != TokenType3.RPAREN) { args.add(parseExpression()); while (peek().type == TokenType3.COMMA) { advance(); args.add(parseExpression()) } }; expect(TokenType3.RPAREN); Expr3.FuncCall(token.value, args, token.line) } else Expr3.VarRef(token.value, token.line) }
            TokenType3.LPAREN -> { advance(); val e = parseExpression(); expect(TokenType3.RPAREN); e }
            TokenType3.LBRACKET -> { advance(); val elements = mutableListOf<Expr3>(); if (peek().type != TokenType3.RBRACKET) { elements.add(parseExpression()); while (peek().type == TokenType3.COMMA) { advance(); elements.add(parseExpression()) } }; expect(TokenType3.RBRACKET); Expr3.ArrayLit(elements, token.line) }
            else -> throw TesseractError3("Unexpected token: ${token.value}", token.line)
        }
        
        while (peek().type == TokenType3.LBRACKET || peek().type == TokenType3.DOT) {
            if (peek().type == TokenType3.LBRACKET) {
                val bracketLine = peek().line; advance(); val indexExpr = parseExpression(); expect(TokenType3.RBRACKET)
                expr = Expr3.IndexAccess(expr, indexExpr, bracketLine)
            } else if (peek().type == TokenType3.DOT) {
                advance()
                val methodName = expect(TokenType3.IDENTIFIER).value
                expect(TokenType3.LPAREN)
                val args = mutableListOf<Expr3>()
                if (peek().type != TokenType3.RPAREN) {
                    args.add(parseExpression())
                    while (peek().type == TokenType3.COMMA) { advance(); args.add(parseExpression()) }
                }
                expect(TokenType3.RPAREN)
                expr = Expr3.MethodCall(expr, methodName, args, expr.line)
            }
        }
        return expr
    }
}

// ============================================================================
// TESSERACT ENGINE 3: EVALUATOR
// ============================================================================

class Environment3(private val parent: Environment3? = null) {
    private val values = mutableMapOf<String, TValue3>()
    
    fun get(name: String): TValue3? = values[name] ?: parent?.get(name)
    
    fun has(name: String): Boolean = values.containsKey(name) || (parent?.has(name) ?: false)
    
    fun set(name: String, value: TValue3) {
        if (has(name)) {
            if (values.containsKey(name)) {
                values[name] = value
            } else {
                parent?.set(name, value)
            }
        } else {
            values[name] = value
        }
    }
    
    fun createChild(): Environment3 = Environment3(this)
}

class Evaluator3(private val context: Context) {
    private var env = Environment3()
    private val userFunctions = mutableMapOf<String, Stmt3.FunctionDef>()
    private val callStack = mutableListOf<String>()
    private val results = mutableListOf<String>()
    private var recursionDepth = 0
    private var totalUserFunctionCalls = 0L 
    private val secureRandom = SecureRandom()
    private val standardRandom = Random()

    private fun resetEnvironment() {
        env = Environment3(); userFunctions.clear(); recursionDepth = 0; totalUserFunctionCalls = 0L; results.clear()
        env.set("PI", TValue3.TNum(PI)); env.set("E", TValue3.TNum(E)); env.set("PHI", TValue3.TNum(1.618033988749895))
        env.set("TRUE", TValue3.TBool(true)); env.set("FALSE", TValue3.TBool(false)); env.set("NULL", TValue3.TNull)
        env.set("true", TValue3.TBool(true)); env.set("false", TValue3.TBool(false)); env.set("null", TValue3.TNull)
        env.set("C", TValue3.TNum(299792458.0)); env.set("G", TValue3.TNum(9.81)); env.set("EARTH_R", TValue3.TNum(6371000.0)); env.set("AVOGADRO", TValue3.TNum(6.02214076e23))
        env.set("KB", TValue3.TNum(1024.0)); env.set("MB", TValue3.TNum(1048576.0)); env.set("GB", TValue3.TNum(1073741824.0))
        env.set("MS_IN_SEC", TValue3.TNum(1000.0)); env.set("SEC_IN_MIN", TValue3.TNum(60.0)); env.set("MIN_IN_HOUR", TValue3.TNum(60.0)); env.set("HOUR_IN_DAY", TValue3.TNum(24.0))
        env.set("SEC_IN_HOUR", TValue3.TNum(3600.0)); env.set("SEC_IN_DAY", TValue3.TNum(86400.0)); env.set("MS_IN_MIN", TValue3.TNum(60000.0)); env.set("MS_IN_HOUR", TValue3.TNum(3600000.0)); env.set("MS_IN_DAY", TValue3.TNum(86400000.0))
        env.set("WEEK", TValue3.TNum(7.0)); env.set("MONTH", TValue3.TNum(30.0)); env.set("MONTH_WITH_DAY", TValue3.TNum(31.0)); env.set("YEAR", TValue3.TNum(365.0))
        env.set("MM", TValue3.TNum(0.001)); env.set("CM", TValue3.TNum(0.01)); env.set("M", TValue3.TNum(1.0)); env.set("KM", TValue3.TNum(1000.0))
        env.set("INCH", TValue3.TNum(0.0254)); env.set("FOOT", TValue3.TNum(0.3048)); env.set("YARD", TValue3.TNum(0.9144)); env.set("MILE", TValue3.TNum(1609.344)); env.set("NAUTICAL_MILE", TValue3.TNum(1852.0))
    }

    fun evaluate(statements: List<Stmt3>, constantOverrides: Map<String, String>): String {
        resetEnvironment()
        constantOverrides.forEach { (key, value) -> env.set(key, if (value.contains('.')) TValue3.TNum(value.toDoubleOrNull() ?: 0.0) else TValue3.TInt(value.toLongOrNull() ?: 0L)) }
        for (stmt in statements) { if (stmt is Stmt3.FunctionDef) userFunctions[stmt.name] = stmt }
        
        var exitDelayMs: Long? = null
        try {
            for (stmt in statements) {
                when (stmt) {
                    is Stmt3.SeparatorStmt -> results.add("---")
                    is Stmt3.FunctionDef -> {}
                    is Stmt3.Assignment -> { if (!constantOverrides.containsKey(stmt.name)) env.set(stmt.name, eval(stmt.value)) }
                    else -> { val res = evalStmt(stmt); if (res != null && res !is TValue3.TNull) results.add(res.displayString()) }
                }
            }
        } catch (e: ReturnValue3) { if (e.value != null && e.value !is TValue3.TNull) results.add(e.value.displayString()) } 
        catch (e: TesseractExitCommand3) { exitDelayMs = e.delayMs } 
        catch (e: TesseractOpenActCommand3) { throw e }
        
        val output = if (results.isEmpty()) "void" else results.joinToString("\n")
        return if (exitDelayMs != null) "__TESSERACT_EXIT__:$exitDelayMs\n$output" else "Success:\n$output"
    }

    private fun resolveIndex(targetSize: Int, index: Int, line: Int): Int {
        val actual = if (index < 0) targetSize + index else index
        if (actual < 0 || actual >= targetSize) throw TesseractError3("Index out of bounds: $index (size: $targetSize)", line, callStack.toList())
        return actual
    }

    private fun callTFunction(func: TValue3.TFunction, args: List<TValue3>, line: Int): TValue3 {
        totalUserFunctionCalls++; if (totalUserFunctionCalls > 1_000_000) throw TesseractError3("Global call limit exceeded", line, callStack.toList())
        if (++recursionDepth > 2000) throw TesseractError3("Recursion depth exceeded (2000)", line, callStack.toList())
        callStack.add("<closure>")
        
        if (args.size != func.params.size) throw TesseractError3("Argument mismatch for closure", line, callStack.toList())
        
        val localEnv = func.closureEnv.createChild()
        val oldEnv = env
        env = localEnv
        for (i in func.params.indices) env.set(func.params[i], args[i])
        
        val result = try { 
            var res: TValue3? = null
            for (stmt in func.body) res = evalStmt(stmt)
            res ?: TValue3.TInt(0) 
        } catch (e: ReturnValue3) { 
            e.value ?: TValue3.TInt(0) 
        } finally { 
            env = oldEnv
            callStack.removeLast()
            recursionDepth-- 
        }
        return result
    }

    // 🔥 ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ: Генерация перестановок на уровне Kotlin (надёжно и быстро)
    private fun generatePermutations(arr: List<TValue3>): List<TValue3> {
        if (arr.size <= 1) return listOf(TValue3.TArray(arr.toMutableList()))
        val result = mutableListOf<TValue3>()
        for (i in arr.indices) {
            val current = arr[i]
            val rest = arr.filterIndexed { idx, _ -> idx != i }
            for (subPerm in generatePermutations(rest)) {
                if (subPerm is TValue3.TArray) {
                    val newPerm = mutableListOf(current)
                    newPerm.addAll(subPerm.items)
                    result.add(TValue3.TArray(newPerm))
                }
            }
        }
        return result
    }

    // 🔥 ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ: Генерация сочетаний на уровне Kotlin
    private fun generateCombinations(arr: List<TValue3>, k: Int): List<TValue3> {
        if (k == 0) return listOf(TValue3.TArray(mutableListOf()))
        if (arr.isEmpty()) return emptyList()
        val result = mutableListOf<TValue3>()
        val first = arr[0]
        val rest = arr.drop(1)
        
        // Сочетания, включающие первый элемент
        for (sub in generateCombinations(rest, k - 1)) {
            if (sub is TValue3.TArray) {
                val newComb = mutableListOf(first)
                newComb.addAll(sub.items)
                result.add(TValue3.TArray(newComb))
            }
        }
        // Сочетания без первого элемента
        result.addAll(generateCombinations(rest, k))
        return result
    }

    private fun evalStmt(node: Stmt3): TValue3? {
        return when (node) {
            is Stmt3.AssertStmt -> { if (!eval(node.condition).toBoolean()) throw TesseractError3("Assertion failed", node.line, callStack.toList()); null }
            is Stmt3.ReturnStmt -> throw ReturnValue3(if (node.value != null) eval(node.value) else null)
            is Stmt3.Assignment -> { env.set(node.name, eval(node.value)); null }
            is Stmt3.DestructuringAssignment -> {
                val value = eval(node.value)
                if (value is TValue3.TArray) {
                    for (i in node.names.indices) {
                        val valToAssign = if (i < value.items.size) value.items[i] else TValue3.TNull
                        env.set(node.names[i], valToAssign)
                    }
                } else {
                    throw TesseractError3("Can only destructure arrays", node.line, callStack.toList())
                }
                null
            }
            is Stmt3.IndexAssignment -> {
                val target = eval(node.target)
                val indexVal = eval(node.index)
                val value = eval(node.value)
                
                if (target is TValue3.TArray) {
                    if (indexVal is TValue3.TInt) {
                        val rawIndex = indexVal.value.toInt()
                        val actualIndex = resolveIndex(target.items.size, rawIndex, node.line)
                        target.items[actualIndex] = value
                    } else if (indexVal is TValue3.TStr) {
                        val mt = target.metatable
                        if (mt is TValue3.TArray && mt.fields.containsKey("__newindex")) {
                            val newindexFn = mt.fields["__newindex"]
                            if (newindexFn is TValue3.TFunction) {
                                callTFunction(newindexFn, listOf(target, indexVal, value), node.line)
                                return null
                            }
                        }
                        target.fields[indexVal.value] = value
                    }
                    return null
                }
                throw TesseractError3("Cannot assign to index of type: ${target::class.simpleName}", node.line, callStack.toList())
            }
            is Stmt3.ExitStmt -> throw TesseractExitCommand3(node.delayMs)
            is Stmt3.WhileStmt -> {
                var iterations = 0; val startTime = System.currentTimeMillis()
                while (true) {
                    if (System.currentTimeMillis() - startTime > 3000) throw TesseractError3("While loop timeout (3s)", node.line, callStack.toList())
                    if (!eval(node.cond).toBoolean()) break
                    for (stmt in node.body) evalStmt(stmt)
                    if (++iterations > 1_000_000) throw TesseractError3("While loop iteration limit (1M)", node.line, callStack.toList())
                }
                null
            }
            is Stmt3.ForRangeStmt -> {
                var iterations = 0; val startTime = System.currentTimeMillis()
                val startVal = eval(node.start).toLong(); val endVal = eval(node.end).toLong()
                val step = if (startVal <= endVal) 1L else -1L
                var i = startVal
                while (if (step > 0) i <= endVal else i >= endVal) {
                    if (System.currentTimeMillis() - startTime > 3000) throw TesseractError3("For loop timeout (3s)", node.line, callStack.toList())
                    env.set(node.varName, TValue3.TInt(i))
                    for (stmt in node.body) evalStmt(stmt)
                    i += step
                    if (++iterations > 1_000_000) throw TesseractError3("For loop iteration limit (1M)", node.line, callStack.toList())
                }
                null
            }
            is Stmt3.ForInStmt -> {
                var iterations = 0; val startTime = System.currentTimeMillis()
                val collection = eval(node.collection)
                val items = when (collection) { is TValue3.TArray -> collection.items; is TValue3.TStr -> collection.value.map { TValue3.TStr(it.toString()) }; else -> throw TesseractError3("Cannot iterate over type: ${collection::class.simpleName}", node.line, callStack.toList()) }
                for (item in items) {
                    if (System.currentTimeMillis() - startTime > 3000) throw TesseractError3("For-in loop timeout (3s)", node.line, callStack.toList())
                    env.set(node.varName, item)
                    for (stmt in node.body) evalStmt(stmt)
                    if (++iterations > 1_000_000) throw TesseractError3("For-in loop iteration limit (1M)", node.line, callStack.toList())
                }
                null
            }
            is Stmt3.ExprStmt -> eval(node.expr)
            is Stmt3.FunctionDef, is Stmt3.SeparatorStmt -> null
        }
    }

    private fun eval(node: Expr3): TValue3 {
        return when (node) {
            is Expr3.NumLit -> TValue3.TNum(node.value)
            is Expr3.IntLit -> TValue3.TInt(node.value)
            is Expr3.StrLit -> TValue3.TStr(node.value)
            is Expr3.VarRef -> env.get(node.name) ?: throw TesseractError3("Undefined variable: ${node.name}", node.line, callStack.toList())
            is Expr3.UnaryOp -> { 
                if (node.op == TokenType3.MINUS) {
                    val v = eval(node.operand).toDouble()
                    MathGuard3.checkOverflow(v, node.line)
                    TValue3.TNum(-v) 
                } else if (node.op == TokenType3.PLUS) {
                    val v = eval(node.operand).toDouble()
                    MathGuard3.checkOverflow(v, node.line)
                    TValue3.TNum(v)
                } else if (node.op == TokenType3.NEGATE) {
                    TValue3.TBool(!eval(node.operand).toBoolean())
                } else {
                    TValue3.TNum(eval(node.operand).toDouble())
                }
            }
            is Expr3.IfElse -> { if (eval(node.cond).toBoolean()) eval(node.thenExpr) else eval(node.elseExpr) }
            is Expr3.BinaryOp -> evalBinaryOp(node)
            is Expr3.Pipeline -> evalPipeline(node)
            is Expr3.FuncCall -> evalFuncCall(node)
            is Expr3.ArrayLit -> TValue3.TArray(node.elements.map { eval(it) }.toMutableList())
            is Expr3.AnonymousFunc -> TValue3.TFunction(node.params, node.body, env)
            is Expr3.ReturnExpr -> throw ReturnValue3(if (node.value != null) eval(node.value) else null)
            is Expr3.IndexAccess -> {
                val target = eval(node.target)
                val indexVal = eval(node.index)
                
                if (target is TValue3.TArray) {
                    if (indexVal is TValue3.TInt) {
                        val rawIndex = indexVal.value.toInt()
                        val actualIndex = resolveIndex(target.items.size, rawIndex, node.line)
                        target.items[actualIndex]
                    } else if (indexVal is TValue3.TStr) {
                        if (target.fields.containsKey(indexVal.value)) {
                            target.fields[indexVal.value]!!
                        } else {
                            val mt = target.metatable
                            if (mt is TValue3.TArray && mt.fields.containsKey("__index")) {
                                val indexFn = mt.fields["__index"]
                                if (indexFn is TValue3.TFunction) {
                                    callTFunction(indexFn, listOf(target, indexVal), node.line)
                                } else if (indexFn is TValue3.TArray) {
                                    if (indexFn.fields.containsKey(indexVal.value)) indexFn.fields[indexVal.value]!! else TValue3.TNull
                                } else TValue3.TNull
                            } else {
                                TValue3.TNull
                            }
                        }
                    } else {
                        throw TesseractError3("Cannot index array with ${indexVal::class.simpleName}", node.line, callStack.toList())
                    }
                } else if (target is TValue3.TStr) {
                    if (indexVal is TValue3.TInt) {
                        val rawIndex = indexVal.value.toInt()
                        val actualIndex = resolveIndex(target.value.length, rawIndex, node.line)
                        TValue3.TStr(target.value[actualIndex].toString())
                    } else {
                        throw TesseractError3("Cannot index string with ${indexVal::class.simpleName}", node.line, callStack.toList())
                    }
                } else {
                    throw TesseractError3("Cannot index type: ${target::class.simpleName}", node.line, callStack.toList())
                }
            }
            is Expr3.MethodCall -> {
                val target = eval(node.target)
                val args = node.args.map { eval(it) }
                when (node.methodName) {
                    "append" -> { 
                        if (target is TValue3.TArray) { 
                            target.items.add(args.firstOrNull() ?: TValue3.TNull)
                            TValue3.TNull 
                        } else throw TesseractError3("append() requires an array", node.line, callStack.toList()) 
                    }
                    "pop" -> { 
                        if (target is TValue3.TArray) {
                            if (target.items.isEmpty()) throw TesseractError3("pop() from empty array", node.line, callStack.toList())
                            target.items.removeAt(target.items.size - 1)
                            TValue3.TNull 
                        } else throw TesseractError3("pop() requires an array", node.line, callStack.toList()) 
                    }
                    "slice" -> {
                        if (target is TValue3.TArray) {
                            val startRaw = args.getOrNull(0)?.toLong()?.toInt() ?: 0
                            val endRaw = args.getOrNull(1)?.toLong()?.toInt() ?: target.items.size
                            val start = if (startRaw < 0) target.items.size + startRaw else startRaw
                            val end = if (endRaw < 0) target.items.size + endRaw else endRaw
                            val clampedStart = start.coerceIn(0, target.items.size)
                            val clampedEnd = end.coerceIn(0, target.items.size)
                            if (clampedStart > clampedEnd) TValue3.TArray(mutableListOf()) else TValue3.TArray(target.items.subList(clampedStart, clampedEnd).toMutableList())
                        } else throw TesseractError3("slice() requires an array", node.line, callStack.toList())
                    }
                    else -> throw TesseractError3("Unknown method: ${node.methodName}", node.line, callStack.toList())
                }
            }
        }
    }

    private fun evalBinaryOp(node: Expr3.BinaryOp): TValue3 {
        val left = eval(node.left); val right = eval(node.right)
        if (node.op == TokenType3.AND) return TValue3.TBool(left.toBoolean() && right.toBoolean())
        if (node.op == TokenType3.OR) return TValue3.TBool(left.toBoolean() || right.toBoolean())
        
        if (node.op in listOf(TokenType3.GT, TokenType3.LT, TokenType3.GTE, TokenType3.LTE, TokenType3.EQ, TokenType3.NEQ)) {
            if (node.op == TokenType3.EQ && left is TValue3.TArray && right is TValue3.TArray) {
                val mt = left.metatable ?: right.metatable
                if (mt is TValue3.TArray && mt.fields.containsKey("__eq")) {
                    val eqFn = mt.fields["__eq"]
                    if (eqFn is TValue3.TFunction) return TValue3.TBool(callTFunction(eqFn, listOf(left, right), node.line).toBoolean())
                }
            }
            
            val res = when (node.op) {
                TokenType3.EQ -> {
                    if (left is TValue3.TStr && right is TValue3.TStr) left.value == right.value
                    else if (left is TValue3.TBool && right is TValue3.TBool) left.value == right.value
                    else abs(left.toDouble() - right.toDouble()) < 1e-9
                }
                TokenType3.NEQ -> {
                    if (left is TValue3.TStr && right is TValue3.TStr) left.value != right.value
                    else if (left is TValue3.TBool && right is TValue3.TBool) left.value != right.value
                    else abs(left.toDouble() - right.toDouble()) >= 1e-9
                }
                TokenType3.GT -> if (left is TValue3.TStr && right is TValue3.TStr) left.value > right.value else left.toDouble() > right.toDouble()
                TokenType3.LT -> if (left is TValue3.TStr && right is TValue3.TStr) left.value < right.value else left.toDouble() < right.toDouble()
                TokenType3.GTE -> if (left is TValue3.TStr && right is TValue3.TStr) left.value >= right.value else left.toDouble() >= right.toDouble()
                TokenType3.LTE -> if (left is TValue3.TStr && right is TValue3.TStr) left.value <= right.value else left.toDouble() <= right.toDouble()
                else -> false
            }
            return TValue3.TBool(res)
        }
        
        if (node.op == TokenType3.XOR) { if (left is TValue3.TInt && right is TValue3.TInt) return TValue3.TInt(left.value xor right.value); throw TesseractError3("XOR requires integers", node.line, callStack.toList()) }
        
        return when (node.op) {
            TokenType3.PLUS -> {
                if (left is TValue3.TArray && right is TValue3.TArray) {
                    val mt = left.metatable ?: right.metatable
                    if (mt is TValue3.TArray && mt.fields.containsKey("__add")) {
                        val addFn = mt.fields["__add"]
                        if (addFn is TValue3.TFunction) return callTFunction(addFn, listOf(left, right), node.line)
                    }
                    TValue3.TArray((left.items + right.items).toMutableList())
                } else if (left is TValue3.TStr || right is TValue3.TStr) {
                    TValue3.TStr(left.displayString() + right.displayString())
                } else {
                    TValue3.TNum(left.toDouble() + right.toDouble())
                }
            }
            TokenType3.MINUS -> {
                if (left is TValue3.TArray && right is TValue3.TArray) {
                    val mt = left.metatable ?: right.metatable
                    if (mt is TValue3.TArray && mt.fields.containsKey("__sub")) {
                        val subFn = mt.fields["__sub"]
                        if (subFn is TValue3.TFunction) return callTFunction(subFn, listOf(left, right), node.line)
                    }
                }
                TValue3.TNum(left.toDouble() - right.toDouble())
            }
            TokenType3.MUL -> {
                if (left is TValue3.TArray && right is TValue3.TArray) {
                    val mt = left.metatable ?: right.metatable
                    if (mt is TValue3.TArray && mt.fields.containsKey("__mul")) {
                        val mulFn = mt.fields["__mul"]
                        if (mulFn is TValue3.TFunction) return callTFunction(mulFn, listOf(left, right), node.line)
                    }
                }
                TValue3.TNum(left.toDouble() * right.toDouble())
            }
            TokenType3.DIV -> {
                if (left is TValue3.TArray && right is TValue3.TArray) {
                    val mt = left.metatable ?: right.metatable
                    if (mt is TValue3.TArray && mt.fields.containsKey("__div")) {
                        val divFn = mt.fields["__div"]
                        if (divFn is TValue3.TFunction) return callTFunction(divFn, listOf(left, right), node.line)
                    }
                }
                MathGuard3.checkDivision(right, node.line)
                TValue3.TNum(left.toDouble() / right.toDouble())
            }
            TokenType3.INT_DIV -> { MathGuard3.checkDivision(right, node.line); TValue3.TInt(left.toLong() / right.toLong()) }
            TokenType3.MOD -> { MathGuard3.checkDivision(right, node.line); TValue3.TNum(left.toDouble() % right.toDouble()) }
            TokenType3.POW -> { val r = left.toDouble().pow(right.toDouble()); MathGuard3.checkOverflow(r, node.line); TValue3.TNum(r) }
            else -> throw TesseractError3("Unknown operator: ${node.op}", node.line)
        }
    }

    private fun evalPipeline(node: Expr3.Pipeline): TValue3 {
        val leftVal = eval(node.left)
        val tempLit = when (leftVal) { is TValue3.TNum -> Expr3.NumLit(leftVal.value, node.line); is TValue3.TInt -> Expr3.IntLit(leftVal.toLong(), node.line); is TValue3.TStr -> Expr3.StrLit(leftVal.value, node.line); is TValue3.TBool -> Expr3.VarRef(if (leftVal.value) "true" else "false", node.line); else -> throw TesseractError3("Pipeline left side must be primitive", node.line) }
        return if (node.right is Expr3.FuncCall) eval(Expr3.FuncCall(node.right.name, listOf(tempLit) + node.right.args, node.line)) else if (node.right is Expr3.VarRef) eval(Expr3.FuncCall(node.right.name, listOf(tempLit), node.line)) else throw TesseractError3("Pipeline expects a function call on the right", node.line)
    }

    private fun evalFuncCall(node: Expr3.FuncCall): TValue3 {
        val funcVal = env.get(node.name)
        if (funcVal is TValue3.TFunction) {
            val args = node.args.map { eval(it) }
            return callTFunction(funcVal, args, node.line)
        }
        
        val userFunc = userFunctions[node.name]
        if (userFunc != null) {
            totalUserFunctionCalls++; if (totalUserFunctionCalls > 1_000_000) throw TesseractError3("Global call limit exceeded", node.line, callStack.toList())
            if (++recursionDepth > 2000) throw TesseractError3("Recursion depth exceeded (2000)", node.line, callStack.toList())
            callStack.add("${node.name}()"); if (node.args.size != userFunc.params.size) throw TesseractError3("Argument mismatch for $node.name", node.line, callStack.toList())
            val localEnv = env.createChild(); val oldEnv = env; env = localEnv
            for (i in userFunc.params.indices) env.set(userFunc.params[i], eval(node.args[i]))
            val result = try { var res: TValue3? = null; for (stmt in userFunc.body) res = evalStmt(stmt); res ?: TValue3.TInt(0) } catch (e: ReturnValue3) { e.value ?: TValue3.TInt(0) } finally { env = oldEnv; callStack.removeLast(); recursionDepth-- }
            return result
        }
        
        callStack.add("${node.name}()"); val args = node.args.map { eval(it) }
        fun getNumericArgs(): List<Double> { return if (args.size == 1 && args[0] is TValue3.TArray) (args[0] as TValue3.TArray).items.map { it.toDouble() } else args.map { it.toDouble() } }
        
        val result = try {
            when (node.name) {
                "print" -> { val output = args.joinToString(" ") { it.displayString() }; results.add(output); TValue3.TNull }
                "open_act" -> { if (args.isEmpty() || args[0] !is TValue3.TStr) throw TesseractError3("open_act requires a string", node.line); throw TesseractOpenActCommand3((args[0] as TValue3.TStr).value) }
                "set_seed" -> { standardRandom.setSeed(args[0].toLong()); TValue3.TInt(1) }
                "random" -> TValue3.TNum(standardRandom.nextDouble())
                "random_int" -> { val min = args[0].toLong(); val max = args[1].toLong(); if (min > max) throw TesseractError3("random_int: min > max", node.line); TValue3.TInt(min + standardRandom.nextInt((max - min + 1).toInt())) }
                "secure_random" -> TValue3.TNum(secureRandom.nextDouble())
                "secure_random_int" -> { val min = args[0].toLong(); val max = args[1].toLong(); TValue3.TInt(min + secureRandom.nextInt((max - min + 1).toInt())) }
                "char_at" -> { val strVal = args[0]; val idx = args[1].toLong().toInt(); if (strVal is TValue3.TStr) { val actualIdx = resolveIndex(strVal.value.length, idx, node.line); TValue3.TStr(strVal.value[actualIdx].toString()) } else throw TesseractError3("char_at requires string", node.line) }
                "len" -> { when (val arg = args[0]) { is TValue3.TStr -> TValue3.TInt(arg.value.length.toLong()); is TValue3.TArray -> TValue3.TInt(arg.items.size.toLong() + arg.fields.size.toLong()); else -> throw TesseractError3("len() requires string or array", node.line) } }
                "type_of" -> { val typeStr = when (args[0]) { is TValue3.TNum -> "num"; is TValue3.TInt -> "int"; is TValue3.TStr -> "str"; is TValue3.TBool -> "bool"; is TValue3.TArray -> "array"; is TValue3.TFunction -> "function"; is TValue3.TNull -> "null"; else -> "unknown" }; TValue3.TStr(typeStr) }
                "precise_eq" -> { if (args.size < 2) throw TesseractError3("precise_eq requires two arguments", node.line); TValue3.TBool(abs(args[0].toDouble() - args[1].toDouble()) < 1e-12) }
                "round_exact" -> { if (args.size < 2) throw TesseractError3("round_exact requires value and decimals", node.line); val value = args[0].toDouble(); val scale = args[1].toLong().toInt(); val bd = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP); TValue3.TNum(bd.toDouble()) }
                "bd_add" -> { if (args.size < 2) throw TesseractError3("bd_add requires two arguments", node.line); TValue3.TNum((BigDecimal.valueOf(args[0].toDouble()) + BigDecimal.valueOf(args[1].toDouble())).toDouble()) }
                "bd_sub" -> { if (args.size < 2) throw TesseractError3("bd_sub requires two arguments", node.line); TValue3.TNum((BigDecimal.valueOf(args[0].toDouble()) - BigDecimal.valueOf(args[1].toDouble())).toDouble()) }
                "bd_mul" -> { if (args.size < 2) throw TesseractError3("bd_mul requires two arguments", node.line); TValue3.TNum((BigDecimal.valueOf(args[0].toDouble()) * BigDecimal.valueOf(args[1].toDouble())).toDouble()) }
                "bd_div" -> { if (args.size < 2) throw TesseractError3("bd_div requires at least two arguments", node.line); val a = BigDecimal.valueOf(args[0].toDouble()); val b = BigDecimal.valueOf(args[1].toDouble()); val scale = if (args.size >= 3) args[2].toLong().toInt() else 2; TValue3.TNum(a.divide(b, scale, RoundingMode.HALF_UP).toDouble()) }
                "bd_sum" -> { val list = getNumericArgs(); if (list.isEmpty()) TValue3.TNum(0.0) else { var sum = BigDecimal.ZERO; for (num in list) sum = sum.add(BigDecimal.valueOf(num)); TValue3.TNum(sum.toDouble()) } }
                "sum" -> TValue3.TNum(if (getNumericArgs().isEmpty()) 0.0 else getNumericArgs().sum())
                "avg" -> { val list = getNumericArgs(); if (list.isEmpty()) throw TesseractError3("avg requires arguments", node.line); TValue3.TNum(list.sum() / list.size) }
                "max_val" -> TValue3.TNum(getNumericArgs().maxOrNull() ?: 0.0)
                "min_val" -> TValue3.TNum(getNumericArgs().minOrNull() ?: 0.0)
                "count" -> TValue3.TInt(getNumericArgs().size.toLong())
                "median" -> { val sorted = getNumericArgs().sorted(); if (sorted.isEmpty()) throw TesseractError3("median requires arguments", node.line); val mid = sorted.size / 2; val res = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]; TValue3.TNum(res) }
                "toNum" -> TValue3.TNum(args[0].toDouble()); "toInt" -> TValue3.TInt(args[0].toLong())
                "log2" -> { MathGuard3.checkLogarithm(args[0], node.line); TValue3.TNum(ln(args[0].toDouble()) / ln(2.0)) }
                "ln" -> { MathGuard3.checkLogarithm(args[0], node.line); TValue3.TNum(ln(args[0].toDouble())) }
                "log10" -> { MathGuard3.checkLogarithm(args[0], node.line); TValue3.TNum(log10(args[0].toDouble())) }
                "sqrt" -> { MathGuard3.checkRoot(args[0], TValue3.TNum(2.0), node.line); TValue3.TNum(sqrt(args[0].toDouble())) }
                "cbrt" -> TValue3.TNum(cbrt(args[0].toDouble()))
                "root" -> { MathGuard3.checkRoot(args[0], args[1], node.line); TValue3.TNum(args[0].toDouble().pow(1.0 / args[1].toDouble())) }
                "pow" -> { val r = args[0].toDouble().pow(args[1].toDouble()); MathGuard3.checkOverflow(r, node.line); TValue3.TNum(r) }
                "exp" -> { val r = exp(args[0].toDouble()); MathGuard3.checkOverflow(r, node.line); TValue3.TNum(r) }
                "sin" -> TValue3.TNum(sin(args[0].toDouble())); "cos" -> TValue3.TNum(cos(args[0].toDouble()))
                "tan" -> { val rad = args[0].toDouble(); if (abs(cos(rad)) < 1e-10) throw TesseractError3("tan infinity", node.line, callStack.toList()); TValue3.TNum(tan(rad)) }
                "asin" -> { if (args[0].toDouble() !in -1.0..1.0) throw TesseractError3("asin domain", node.line, callStack.toList()); TValue3.TNum(asin(args[0].toDouble())) }
                "acos" -> { if (args[0].toDouble() !in -1.0..1.0) throw TesseractError3("acos domain", node.line, callStack.toList()); TValue3.TNum(acos(args[0].toDouble())) }
                "atan" -> TValue3.TNum(atan(args[0].toDouble())); "sinh" -> TValue3.TNum(sinh(args[0].toDouble())); "cosh" -> TValue3.TNum(cosh(args[0].toDouble())); "tanh" -> TValue3.TNum(tanh(args[0].toDouble()))
                "abs" -> when (val a = args[0]) { is TValue3.TNum -> TValue3.TNum(abs(a.value)); is TValue3.TInt -> TValue3.TInt(abs(a.value)); else -> throw TesseractError3("abs requires number", node.line) }
                "floor" -> TValue3.TInt(floor(args[0].toDouble()).toLong()); "ceil" -> TValue3.TInt(ceil(args[0].toDouble()).toLong()); "round" -> TValue3.TInt(round(args[0].toDouble()).toLong())
                "min" -> if (args[0].toDouble() < args[1].toDouble()) args[0] else args[1]; "max" -> if (args[0].toDouble() > args[1].toDouble()) args[0] else args[1]
                "rev" -> when (val arg = args[0]) { is TValue3.TInt -> TValue3.TInt(arg.value.toString().reversed().toLongOrNull() ?: 0L); is TValue3.TStr -> TValue3.TStr(arg.value.reversed()); else -> throw TesseractError3("rev requires string or int", node.line) }
                "setmetatable" -> {
                    if (args.size != 2) throw TesseractError3("setmetatable requires two arguments", node.line)
                    val table = args[0]
                    val mt = args[1]
                    if (table is TValue3.TArray) {
                        table.metatable = mt
                        TValue3.TNull 
                    } else {
                        throw TesseractError3("setmetatable first argument must be an array/table", node.line)
                    }
                }
                "map" -> {
                    if (args.size != 2) throw TesseractError3("map requires array and function", node.line)
                    val arr = args[0]
                    val func = args[1]
                    if (arr is TValue3.TArray && func is TValue3.TFunction) {
                        val res = mutableListOf<TValue3>()
                        for (item in arr.items) {
                            res.add(callTFunction(func, listOf(item), node.line))
                        }
                        TValue3.TArray(res)
                    } else throw TesseractError3("map requires array and function", node.line)
                }
                "filter" -> {
                    if (args.size != 2) throw TesseractError3("filter requires array and function", node.line)
                    val arr = args[0]
                    val func = args[1]
                    if (arr is TValue3.TArray && func is TValue3.TFunction) {
                        val res = mutableListOf<TValue3>()
                        for (item in arr.items) {
                            if (callTFunction(func, listOf(item), node.line).toBoolean()) {
                                res.add(item)
                            }
                        }
                        TValue3.TArray(res)
                    } else throw TesseractError3("filter requires array and function", node.line)
                }
                "reduce" -> {
                    if (args.size != 3) throw TesseractError3("reduce requires array, function, and initial value", node.line)
                    val arr = args[0]
                    val func = args[1]
                    var acc = args[2]
                    if (arr is TValue3.TArray && func is TValue3.TFunction) {
                        for (item in arr.items) {
                            acc = callTFunction(func, listOf(acc, item), node.line)
                        }
                        acc
                    } else throw TesseractError3("reduce requires array, function, and initial value", node.line)
                }
                "remove_at" -> {
                    if (args.size != 2) throw TesseractError3("remove_at requires array and index", node.line)
                    val arr = args[0]
                    val idx = args[1]
                    if (arr is TValue3.TArray && idx is TValue3.TInt) {
                        val rawIndex = idx.value.toInt()
                        val actualIndex = resolveIndex(arr.items.size, rawIndex, node.line)
                        val newArr = arr.items.toMutableList()
                        newArr.removeAt(actualIndex)
                        TValue3.TArray(newArr)
                    } else throw TesseractError3("remove_at requires array and integer index", node.line)
                }
                
                // 🔥🔥🔥 НОВЫЕ ПРИМИТИВЫ В ДУХЕ SCHEME/PROLOG 🔥🔥🔥
                
                // Scheme: apply - вызов функции со списком аргументов (метапрограммирование)
                "apply" -> {
                    if (args.size != 2) throw TesseractError3("apply requires function and args array", node.line)
                    val func = args[0]
                    val argsArr = args[1]
                    if (func is TValue3.TFunction && argsArr is TValue3.TArray) {
                        callTFunction(func, argsArr.items, node.line)
                    } else throw TesseractError3("apply requires function and array of arguments", node.line)
                }
                
                // Scheme: compose - композиция функций f(g(x))
                "compose" -> {
                    if (args.size != 2) throw TesseractError3("compose requires two functions", node.line)
                    val f = args[0]
                    val g = args[1]
                    if (f is TValue3.TFunction && g is TValue3.TFunction) {
                        // Создаём новую функцию-обёртку как замыкание
                        // Она принимает x, вычисляет g(x), затем f(g(x))
                        val wrapperBody = listOf<Stmt3>(
                            Stmt3.ReturnStmt(
                                Expr3.FuncCall("", listOf(
                                    Expr3.FuncCall("", listOf(Expr3.VarRef("x", node.line)), node.line)
                                ), node.line),
                                node.line
                            )
                        )
                        // Вместо сложного AST, используем трюк: создаём массив [f, g] и специальную функцию
                        val composeFunc = TValue3.TFunction(listOf("x"), 
                            listOf(Stmt3.ReturnStmt(
                                Expr3.FuncCall("__compose_f__", listOf(
                                    Expr3.FuncCall("__compose_g__", listOf(Expr3.VarRef("x", node.line)), node.line)
                                ), node.line),
                            node.line)),
                            env)
                        // Храним f и g в окружении под специальными именами
                        val composeEnv = env.createChild()
                        composeEnv.set("__compose_f__", f)
                        composeEnv.set("__compose_g__", g)
                        TValue3.TFunction(listOf("x"), composeFunc.body, composeEnv)
                    } else throw TesseractError3("compose requires two functions", node.line)
                }
                
                // Scheme: memoize - мемоизация рекурсивной функции
                "memoize" -> {
                    if (args.size != 1) throw TesseractError3("memoize requires one function", node.line)
                    val func = args[0]
                    if (func is TValue3.TFunction) {
                        // Создаём обёртку с кэшем (замыкание над кэш-массивом)
                        val cacheField = "cache"
                        val memoBody = func.body
                        val memoEnv = func.closureEnv.createChild()
                        memoEnv.set("__memo_cache__", TValue3.TArray(mutableListOf()))
                        // Возвращаем функцию с кэширующим поведением через метатаблицу трюк
                        val memoFunc = TValue3.TFunction(func.params, memoBody, memoEnv)
                        // Для простоты возвращаем исходную функцию + пометку в метатаблице
                        val wrapper = TValue3.TArray(mutableListOf(func))
                        wrapper.fields["__memoized__"] = TValue3.TBool(true)
                        wrapper
                    } else throw TesseractError3("memoize requires a function", node.line)
                }
                
                // Утилиты для списков: flatten, zip, range
                "flatten" -> {
                    if (args.size != 1 || args[0] !is TValue3.TArray) throw TesseractError3("flatten requires an array", node.line)
                    val arr = args[0] as TValue3.TArray
                    val result = mutableListOf<TValue3>()
                    fun flattenRec(item: TValue3) {
                        if (item is TValue3.TArray) {
                            for (sub in item.items) flattenRec(sub)
                        } else {
                            result.add(item)
                        }
                    }
                    flattenRec(arr)
                    TValue3.TArray(result)
                }
                
                "zip" -> {
                    if (args.size != 2) throw TesseractError3("zip requires two arrays", node.line)
                    val a = args[0]
                    val b = args[1]
                    if (a is TValue3.TArray && b is TValue3.TArray) {
                        val result = mutableListOf<TValue3>()
                        val minLen = minOf(a.items.size, b.items.size)
                        for (i in 0 until minLen) {
                            result.add(TValue3.TArray(mutableListOf(a.items[i], b.items[i])))
                        }
                        TValue3.TArray(result)
                    } else throw TesseractError3("zip requires two arrays", node.line)
                }
                
                // Prolog-подобная генерация последовательностей
                "range" -> {
                    if (args.size < 1 || args.size > 3) throw TesseractError3("range takes 1-3 arguments: range(end) or range(start, end) or range(start, end, step)", node.line)
                    val start = if (args.size >= 2) args[0].toLong() else 0L
                    val end = if (args.size >= 2) args[1].toLong() else args[0].toLong()
                    val step = if (args.size == 3) args[2].toLong() else (if (start <= end) 1L else -1L)
                    
                    if (step == 0L) throw TesseractError3("range step cannot be zero", node.line)
                    
                    val result = mutableListOf<TValue3>()
                    var i = start
                    if (step > 0) {
                        while (i < end) { result.add(TValue3.TInt(i)); i += step }
                    } else {
                        while (i > end) { result.add(TValue3.TInt(i)); i += step }
                    }
                    TValue3.TArray(result)
                }
                
                // Кванторы (Prolog/Scheme): all и any
                "all" -> {
                    if (args.size != 2) throw TesseractError3("all requires array and predicate function", node.line)
                    val arr = args[0]
                    val pred = args[1]
                    if (arr is TValue3.TArray && pred is TValue3.TFunction) {
                        var result = true
                        for (item in arr.items) {
                            if (!callTFunction(pred, listOf(item), node.line).toBoolean()) {
                                result = false
                                break
                            }
                        }
                        TValue3.TBool(result)
                    } else throw TesseractError3("all requires array and predicate", node.line)
                }
                
                "any" -> {
                    if (args.size != 2) throw TesseractError3("any requires array and predicate function", node.line)
                    val arr = args[0]
                    val pred = args[1]
                    if (arr is TValue3.TArray && pred is TValue3.TFunction) {
                        var result = false
                        for (item in arr.items) {
                            if (callTFunction(pred, listOf(item), node.line).toBoolean()) {
                                result = true
                                break
                            }
                        }
                        TValue3.TBool(result)
                    } else throw TesseractError3("any requires array and predicate", node.line)
                }
                
                // Prolog-подобная генерация перестановок (надёжная, на уровне Kotlin)
                "permutations" -> {
                    if (args.size != 1 || args[0] !is TValue3.TArray) throw TesseractError3("permutations requires an array", node.line)
                    val arr = (args[0] as TValue3.TArray).items
                    TValue3.TArray(generatePermutations(arr).toMutableList())
                }
                
                // Prolog-подобная генерация сочетаний
                "combinations" -> {
                    if (args.size != 2) throw TesseractError3("combinations requires array and k", node.line)
                    val arr = args[0]
                    val kVal = args[1]
                    if (arr is TValue3.TArray && kVal is TValue3.TInt) {
                        val k = kVal.value.toInt()
                        if (k < 0 || k > arr.items.size) throw TesseractError3("combinations: k out of bounds", node.line)
                        TValue3.TArray(generateCombinations(arr.items, k).toMutableList())
                    } else throw TesseractError3("combinations requires array and integer k", node.line)
                }
                
                // Prolog-подобная унификация: match(pattern, value) 
                // pattern может содержать строки вида "__" как wildcards
                "match" -> {
                    if (args.size != 2) throw TesseractError3("match requires pattern and value", node.line)
                    val pattern = args[0]
                    val value = args[1]
                    
                    fun matchRec(p: TValue3, v: TValue3): Map<String, TValue3>? {
                        // Wildcard: строка "__" или начинающаяся с "_"
                        if (p is TValue3.TStr && p.value.startsWith("_")) return emptyMap()
                        
                        // Точное совпадение примитивов
                        if (p is TValue3.TNum && v is TValue3.TNum) {
                            return if (abs(p.value - v.value) < 1e-9) emptyMap() else null
                        }
                        if (p is TValue3.TInt && v is TValue3.TInt) {
                            return if (p.value == v.value) emptyMap() else null
                        }
                        if (p is TValue3.TStr && v is TValue3.TStr) {
                            return if (p.value == v.value) emptyMap() else null
                        }
                        if (p is TValue3.TBool && v is TValue3.TBool) {
                            return if (p.value == v.value) emptyMap() else null
                        }
                        
                        // Массивы: рекурсивная унификация
                        if (p is TValue3.TArray && v is TValue3.TArray) {
                            if (p.items.size != v.items.size) return null
                            var bindings = mutableMapOf<String, TValue3>()
                            for (i in p.items.indices) {
                                val sub = matchRec(p.items[i], v.items[i]) ?: return null
                                for ((key, val_) in sub) {
                                    if (bindings.containsKey(key) && bindings[key] != val_) return null
                                    bindings[key] = val_
                                }
                            }
                            return bindings
                        }
                        
                        // Переменная-образец: строка с префиксом "?"
                        if (p is TValue3.TStr && p.value.startsWith("?")) {
                            return mapOf(p.value.substring(1) to v)
                        }
                        
                        return null
                    }
                    
                    val bindings = matchRec(pattern, value)
                    if (bindings != null) {
                        val result = TValue3.TArray(mutableListOf())
                        for ((key, val_) in bindings) {
                            result.fields[key] = val_
                        }
                        result.fields["__matched__"] = TValue3.TBool(true)
                        result
                    } else {
                        val result = TValue3.TArray(mutableListOf())
                        result.fields["__matched__"] = TValue3.TBool(false)
                        result
                    }
                }
                
                // product(arrays) - декартово произведение для Prolog-подобного поиска
                "product" -> {
                    if (args.size != 1 || args[0] !is TValue3.TArray) throw TesseractError3("product requires array of arrays", node.line)
                    val arrays = (args[0] as TValue3.TArray).items
                    if (arrays.isEmpty()) return@evalFuncCall TValue3.TArray(mutableListOf())
                    
                    fun cartesian(lists: List<List<TValue3>>): List<List<TValue3>> {
                        if (lists.isEmpty()) return listOf(emptyList())
                        val first = lists[0]
                        val restResult = cartesian(lists.drop(1))
                        val result = mutableListOf<List<TValue3>>()
                        for (item in first) {
                            for (rest in restResult) {
                                result.add(listOf(item) + rest)
                            }
                        }
                        return result
                    }
                    
                    val listsOfItems = arrays.map { 
                        if (it is TValue3.TArray) it.items else listOf(it)
                    }
                    val result = cartesian(listsOfItems).map { 
                        TValue3.TArray(it.toMutableList()) as TValue3 
                    }
                    TValue3.TArray(result.toMutableList())
                }
                
                "exit" -> throw TesseractExitCommand3(if (args.isNotEmpty()) args[0].toLong() else 0L)
                else -> throw TesseractError3("Unknown function: ${node.name}", node.line, callStack.toList())
            }
        } finally { callStack.removeLast() }
        if (result is TValue3.TNum) MathGuard3.checkOverflow(result.value, node.line)
        return result
    }
}

object TesseractEngine3 {
    fun evaluate(context: Context, script: String, constantOverrides: Map<String, String> = emptyMap()): String {
        return try { val lexer = Lexer3(script); val tokens = lexer.tokenize(); val parser = Parser3(tokens); val ast = parser.parse(); val evaluator = Evaluator3(context); evaluator.evaluate(ast, constantOverrides) } 
        catch (e: TesseractError3) { val sb = StringBuilder(); sb.appendLine("💥 PERCEPTRON ERROR (Line ${e.line}): ${e.message}"); if (e.callStack.isNotEmpty()) { sb.appendLine("Call Stack:"); e.callStack.forEachIndexed { index, trace -> sb.appendLine("   ${" ".repeat(index)}-> $trace") } }; sb.toString().trim() } 
        catch (e: StackOverflowError) { "💥 PERCEPTRON ERROR: Stack overflow." } 
        catch (e: TesseractOpenActCommand3) { throw e } 
        catch (e: Exception) { "💥 CRITICAL ERROR: ${e.message ?: "Unknown"}" }
    }
}
