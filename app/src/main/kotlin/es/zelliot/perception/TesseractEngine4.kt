package es.zelliot.perceptron

import android.content.Context
import kotlin.math.*
import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.util.Random

// ============================================================================
// TESSERACT ENGINE 4: EXCEPTIONS AND DATA TYPES
// ============================================================================

class TesseractError4(message: String, val line: Int, val callStack: List<String> = emptyList(), vararg val formatArgs: Any) : Exception(message)
class ReturnValue4(val value: TValue4?) : Exception()
class TesseractExitCommand4(val delayMs: Long) : Exception()
class TesseractOpenActCommand4(val packageName: String) : Exception()

sealed class TValue4 {
    data class TNum(val value: Double) : TValue4()
    data class TInt(val value: Long) : TValue4()
    data class TBigInt(val value: BigInteger) : TValue4()
    data class TComplex(val re: Double, val im: Double) : TValue4()
    data class TMatrix(val rows: Int, val cols: Int, val data: DoubleArray) : TValue4() {
        override fun equals(other: Any?): Boolean = other is TMatrix && rows == other.rows && cols == other.cols && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * (31 * rows + cols) + data.contentHashCode()
    }
    data class TRational(val num: BigInteger, val den: BigInteger) : TValue4()
    data class TPoly(val coeffs: List<Double>) : TValue4()
    
    data class TArray(val items: MutableList<TValue4> = mutableListOf(), val fields: MutableMap<String, TValue4> = mutableMapOf(), var metatable: TValue4? = null) : TValue4()
    data class TFunction(val params: List<String>, val body: List<Stmt4>, val closureEnv: Environment4) : TValue4()
    data class TStr(val value: String) : TValue4()
    data class TBool(val value: Boolean) : TValue4()
    object TNull : TValue4()

    fun toDouble(line: Int = 0): Double = when (this) { 
        is TNum -> value
        is TInt -> value.toDouble()
        is TBool -> if (value) 1.0 else 0.0
        is TComplex -> if (im == 0.0) re else throw TesseractError4("Cannot convert complex to real", line)
        is TBigInt -> value.toDouble()
        is TRational -> num.toDouble() / den.toDouble()
        is TStr -> value.toDoubleOrNull() ?: throw TesseractError4("Cannot convert string to number", line)
        else -> throw TesseractError4("Expected number, got ${this::class.simpleName}", line) 
    }
    
    fun toLong(line: Int = 0): Long = when (this) { 
        is TNum -> value.toLong()
        is TInt -> value
        is TBool -> if (value) 1L else 0L
        is TBigInt -> value.toLong()
        is TRational -> (num / den).toLong()
        is TStr -> value.toLongOrNull() ?: throw TesseractError4("Cannot convert string to integer", line)
        else -> throw TesseractError4("Expected integer, got ${this::class.simpleName}", line) 
    }
    
    fun toBoolean(): Boolean = when (this) {
        is TBool -> value; is TNum -> value != 0.0; is TInt -> value != 0L; is TBigInt -> value != BigInteger.ZERO
        is TStr -> value.isNotEmpty(); is TArray -> items.isNotEmpty() || fields.isNotEmpty(); is TNull -> false
        is TFunction -> true; is TComplex -> re != 0.0 || im != 0.0; is TMatrix -> data.any { it != 0.0 }
        is TRational -> num != BigInteger.ZERO
        else -> false
    }
    
    fun displayString(): String = when (this) {
        is TNum -> if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong().toString() else value.toString()
        is TInt -> value.toString(); is TBigInt -> value.toString(); is TStr -> value
        is TBool -> if (value) "true" else "false"
        is TComplex -> { 
            val r = if (re % 1.0 == 0.0 && abs(re) < 1e15) re.toLong().toString() else re.toString()
            val iAbs = abs(im)
            val iStr = if (iAbs % 1.0 == 0.0 && iAbs < 1e15) iAbs.toLong().toString() else iAbs.toString()
            val sign = if (im < 0) " - " else " + "
            when {
                im == 0.0 -> r
                re == 0.0 -> "${if (im < 0) "-" else ""}${iStr}i"
                else -> "$r$sign${iStr}i"
            }
        }
        is TMatrix -> "Matrix(${rows}x${cols})"
        is TRational -> if (den == BigInteger.ONE) num.toString() else "${num}/${den}"
        is TPoly -> { 
            if (coeffs.isEmpty()) "0"
            else {
                val terms = mutableListOf<String>()
                for (i in coeffs.indices.reversed()) {
                    val c = coeffs[i]
                    if (c == 0.0) continue
                    val absC = abs(c)
                    val sign = if (c < 0) " - " else if (terms.isEmpty()) "" else " + "
                    val coefStr = if (absC % 1.0 == 0.0 && absC < 1e15) absC.toLong().toString() else absC.toString()
                    
                    val term = when (i) {
                        0 -> "$sign$coefStr"
                        1 -> "$sign${if (absC == 1.0) "" else coefStr}x"
                        else -> "$sign${if (absC == 1.0) "" else coefStr}x^$i"
                    }
                    terms.add(term)
                }
                if (terms.isEmpty()) "0" else terms.joinToString("")
            }
        }
        is TArray -> { 
            val itemsStr = items.joinToString(", ") { it.displayString() }
            if (fields.isEmpty()) "[$itemsStr]" else "{items: [$itemsStr], fields: {${fields.map { "${it.key}=${it.value.displayString()}" }.joinToString(", ")}}}" 
        }
        is TFunction -> "<function>"; is TNull -> "null"
    }
}

object MathGuard4 {
    fun checkDivision(b: TValue4, line: Int) { if (b is TValue4.TBigInt && b.value == BigInteger.ZERO || b.toDouble(line) == 0.0) throw TesseractError4("Division by zero", line) }
    fun checkOverflow(result: Double, line: Int) { if (result.isInfinite()) throw TesseractError4("Numeric overflow", line); if (result.isNaN()) throw TesseractError4("Not a Number (NaN)", line) }
}

// ============================================================================
// TESSERACT ENGINE 4: LEXER & AST
// ============================================================================

enum class TokenType4 {
    NUMBER, STRING, IDENTIFIER, PLUS, MINUS, MUL, DIV, INT_DIV, MOD, POW, XOR,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, PIPE, ASSIGN, COLON, ARROW, DOT,
    GT, LT, GTE, LTE, EQ, NEQ, AND, OR, NEGATE,
    FN, VAL, CONST, RETURN, ASSERT, IF, THEN, ELSE, WHILE, DO, FOR, IN, TO, SEPARATOR, EXIT, EOF
}
data class Token4(val type: TokenType4, val value: String, val line: Int)

class Lexer4(private val source: String) {
    private var pos = 0; private var line = 1; private val tokens = mutableListOf<Token4>()
    private fun currentChar(): Char = if (pos < source.length) source[pos] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (pos + offset < source.length) source[pos + offset] else '\u0000'
    private fun advance(): Char { val c = currentChar(); pos++; if (c == '\n') line++; return c }
    private fun addToken(type: TokenType4, value: String) { tokens.add(Token4(type, value, line)) }

    fun tokenize(): List<Token4> {
        while (pos < source.length) {
            val c = currentChar()
            when {
                c.isWhitespace() -> advance(); c == '\uFEFF' -> advance(); c == ';' -> advance()
                c == '#' -> { while (pos < source.length && currentChar() != '\n') advance() }
                c.isDigit() || (c == '.' && peek().isDigit()) -> readNumber()
                c == '"' -> readString()
                c.isLetter() || c == '_' -> readIdentifier()
                c == '+' -> { addToken(TokenType4.PLUS, "+"); advance() }
                c == '-' -> { when { peek() == '>' -> { addToken(TokenType4.ARROW, "->"); advance(); advance() }; peek() == '-' && peek(2) == '-' -> { addToken(TokenType4.SEPARATOR, "---"); advance(); advance(); advance() }; else -> { addToken(TokenType4.MINUS, "-"); advance() } } }
                c == '*' -> { if (peek() == '*') { addToken(TokenType4.POW, "**"); advance(); advance() } else { addToken(TokenType4.MUL, "*"); advance() } }
                c == '/' -> { if (peek() == '/') { addToken(TokenType4.INT_DIV, "//"); advance(); advance() } else { addToken(TokenType4.DIV, "/"); advance() } }
                c == '%' -> { addToken(TokenType4.MOD, "%"); advance() }
                c == '^' -> { if (peek() == '^') { addToken(TokenType4.XOR, "^^"); advance(); advance() } else { addToken(TokenType4.POW, "^"); advance() } }
                c == '>' -> { if (peek() == '=') { addToken(TokenType4.GTE, ">="); advance(); advance() } else { addToken(TokenType4.GT, ">"); advance() } }
                c == '<' -> { if (peek() == '=') { addToken(TokenType4.LTE, "<="); advance(); advance() } else { addToken(TokenType4.LT, "<"); advance() } }
                c == '=' -> { if (peek() == '=') { addToken(TokenType4.EQ, "=="); advance(); advance() } else { addToken(TokenType4.ASSIGN, "="); advance() } }
                c == '!' -> { if (peek() == '=') { addToken(TokenType4.NEQ, "!="); advance(); advance() } else { addToken(TokenType4.NEGATE, "!"); advance() } }
                c == '&' -> { if (peek() == '&') { addToken(TokenType4.AND, "&&"); advance(); advance() } else throw TesseractError4("Unknown character: &", line) }
                c == '|' -> { when { peek() == '|' -> { addToken(TokenType4.OR, "||"); advance(); advance() }; peek() == '>' -> { addToken(TokenType4.PIPE, "|>"); advance(); advance() }; else -> throw TesseractError4("Unknown character: |", line) } }
                c == '(' -> { addToken(TokenType4.LPAREN, "("); advance() }; c == ')' -> { addToken(TokenType4.RPAREN, ")"); advance() }
                c == '{' -> { addToken(TokenType4.LBRACE, "{"); advance() }; c == '}' -> { addToken(TokenType4.RBRACE, "}"); advance() }
                c == '[' -> { addToken(TokenType4.LBRACKET, "["); advance() }; c == ']' -> { addToken(TokenType4.RBRACKET, "]"); advance() }
                c == ',' -> { addToken(TokenType4.COMMA, ","); advance() }; c == ':' -> { addToken(TokenType4.COLON, ":"); advance() }; c == '.' -> { addToken(TokenType4.DOT, "."); advance() }
                else -> throw TesseractError4("Unknown character: $c", line)
            }
        }
        tokens.add(Token4(TokenType4.EOF, "", line)); return tokens
    }

    private fun readNumber() { 
        val start = pos; 
        while (pos < source.length && (currentChar().isDigit() || currentChar() == '.')) advance()
        if (currentChar() == 'e' || currentChar() == 'E') { advance(); if (currentChar() == '+' || currentChar() == '-') advance(); while (pos < source.length && currentChar().isDigit()) advance() }
        var numStr = source.substring(start, pos)
        if (currentChar() == 'i' || currentChar() == 'j') { advance(); addToken(TokenType4.NUMBER, numStr + "i") } 
        else addToken(TokenType4.NUMBER, numStr) 
    }
    
    private fun readString() {
        advance(); val sb = StringBuilder()
        while (pos < source.length && currentChar() != '"') {
            if (currentChar() == '\\') { advance(); when (currentChar()) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); '\\' -> sb.append('\\'); '"' -> sb.append('"'); else -> sb.append(currentChar()) } } else sb.append(currentChar())
            advance()
        }
        addToken(TokenType4.STRING, sb.toString()); if (pos < source.length && currentChar() == '"') advance()
    }

    private fun readIdentifier() { 
        val start = pos; while (pos < source.length && (currentChar().isLetterOrDigit() || currentChar() == '_')) advance()
        var word = source.substring(start, pos).replace('а', 'a').replace('А', 'A').replace('в', 'v').replace('В', 'V').replace('е', 'e').replace('Е', 'E').replace('о', 'o').replace('О', 'O').replace('р', 'r').replace('Р', 'R').replace('с', 'c').replace('С', 'C').replace('у', 'y').replace('У', 'Y').replace('х', 'x').replace('Х', 'X')
        if (word == "ate") throw TesseractError4("Typo: 'ate'", line)
        
        // ИСПРАВЛЕНИЕ 1: Добавлено "let" в список ключевых слов deklarации переменных
        val type = when (word.lowercase()) { 
            "fn" -> TokenType4.FN; "val", "var", "let" -> TokenType4.VAL; "const" -> TokenType4.CONST; "return" -> TokenType4.RETURN; "assert" -> TokenType4.ASSERT; 
            "if" -> TokenType4.IF; "then" -> TokenType4.THEN; "else" -> TokenType4.ELSE; "while" -> TokenType4.WHILE; "do" -> TokenType4.DO; "for" -> TokenType4.FOR; 
            "in" -> TokenType4.IN; "to" -> TokenType4.TO; "exit" -> TokenType4.EXIT; "and" -> TokenType4.AND; "or" -> TokenType4.OR; "not", "negate" -> TokenType4.NEGATE
            else -> TokenType4.IDENTIFIER 
        }
        addToken(type, word) 
    }
}

sealed class Node4 { abstract val line: Int }
sealed class Expr4 : Node4() {
    data class NumLit(val value: Double, override val line: Int) : Expr4()
    data class IntLit(val value: Long, override val line: Int) : Expr4()
    data class BigIntLit(val value: BigInteger, override val line: Int) : Expr4()
    data class ComplexLit(val re: Double, val im: Double, override val line: Int) : Expr4()
    data class StrLit(val value: String, override val line: Int) : Expr4()
    data class VarRef(val name: String, override val line: Int) : Expr4()
    data class BinaryOp(val left: Expr4, val op: TokenType4, val right: Expr4, override val line: Int) : Expr4()
    data class UnaryOp(val op: TokenType4, val operand: Expr4, override val line: Int) : Expr4()
    data class FuncCall(val name: String, val args: List<Expr4>, override val line: Int) : Expr4()
    data class Pipeline(val left: Expr4, val right: Expr4, override val line: Int) : Expr4()
    data class IfElse(val cond: Expr4, val thenExpr: Expr4, val elseExpr: Expr4, override val line: Int) : Expr4()
    data class ArrayLit(val elements: List<Expr4>, override val line: Int) : Expr4()
    data class IndexAccess(val target: Expr4, val index: Expr4, override val line: Int) : Expr4()
    data class MethodCall(val target: Expr4, val methodName: String, val args: List<Expr4>, override val line: Int) : Expr4()
    data class AnonymousFunc(val params: List<String>, val body: List<Stmt4>, override val line: Int) : Expr4()
    data class ReturnExpr(val value: Expr4?, override val line: Int) : Expr4()
    data class BlockExpr(val statements: List<Stmt4>, override val line: Int) : Expr4()
}
sealed class Stmt4 : Node4() {
    data class Assignment(val name: String, val value: Expr4, override val line: Int, val isDeclaration: Boolean = false) : Stmt4()
    data class IndexAssignment(val target: Expr4, val index: Expr4, val value: Expr4, override val line: Int) : Stmt4()
    data class DestructuringAssignment(val names: List<String>, val value: Expr4, override val line: Int, val isDeclaration: Boolean = false) : Stmt4()
    data class FunctionDef(val name: String, val params: List<String>, val body: List<Stmt4>, override val line: Int) : Stmt4()
    data class AssertStmt(val condition: Expr4, val message: Expr4?, override val line: Int) : Stmt4()
    data class ReturnStmt(val value: Expr4?, override val line: Int) : Stmt4()
    data class WhileStmt(val cond: Expr4, val body: List<Stmt4>, override val line: Int) : Stmt4()
    data class ForRangeStmt(val varName: String, val start: Expr4, val end: Expr4, val body: List<Stmt4>, override val line: Int) : Stmt4()
    data class ForInStmt(val varName: String, val collection: Expr4, val body: List<Stmt4>, override val line: Int) : Stmt4()
    data class ExprStmt(val expr: Expr4, override val line: Int) : Stmt4()
    data class ExitStmt(val delayMs: Long, override val line: Int) : Stmt4()
    object SeparatorStmt : Stmt4() { override val line: Int = 0 }
}

class Parser4(private val tokens: List<Token4>) {
    private var pos = 0
    private fun peek(offset: Int = 0): Token4 = tokens.getOrNull(pos + offset) ?: Token4(TokenType4.EOF, "", 0)
    private fun advance(): Token4 = tokens[pos++]
    private fun expect(type: TokenType4): Token4 { val current = peek(); if (current.type != type) throw TesseractError4("Expected $type, got ${current.value}", current.line); return advance() }

    fun parse(): List<Stmt4> { val statements = mutableListOf<Stmt4>(); while (peek().type != TokenType4.EOF) { if (peek().type == TokenType4.SEPARATOR) { advance(); statements.add(Stmt4.SeparatorStmt) } else statements.add(parseStatement()) }; return statements }

    private fun parseStatement(): Stmt4 {
        val current = peek()
        return when (current.type) {
            TokenType4.FN -> parseFunctionDef()
            TokenType4.ASSERT -> { 
                val currentToken = peek()
                advance()
                var hasParen = false
                if (peek().type == TokenType4.LPAREN) {
                    advance()
                    hasParen = true
                }
                val cond = parseExpression()
                var msg: Expr4? = null
                if (peek().type == TokenType4.COMMA) {
                    advance()
                    msg = parseExpression()
                }
                if (hasParen) {
                    expect(TokenType4.RPAREN)
                }
                Stmt4.AssertStmt(cond, msg, currentToken.line) 
            }
            TokenType4.RETURN -> { advance(); val hasValue = peek().type != TokenType4.EOF && peek().type != TokenType4.RBRACE && peek().type != TokenType4.SEPARATOR; Stmt4.ReturnStmt(if (hasValue) parseExpression() else null, current.line) }
            TokenType4.WHILE -> parseWhile(); TokenType4.FOR -> parseFor()
            TokenType4.EXIT -> { advance(); val delay = if (peek().type == TokenType4.NUMBER) advance().value.toLong() else 0L; Stmt4.ExitStmt(delay, current.line) }
            TokenType4.VAL, TokenType4.CONST -> {
                advance()
                if (peek().type == TokenType4.LBRACKET) { advance(); val names = mutableListOf<String>(); if (peek().type != TokenType4.RBRACKET) { do { names.add(expect(TokenType4.IDENTIFIER).value); if (peek().type == TokenType4.COMMA) advance() else break } while (peek().type != TokenType4.RBRACKET) }; expect(TokenType4.RBRACKET); expect(TokenType4.ASSIGN); Stmt4.DestructuringAssignment(names, parseExpression(), current.line, isDeclaration = true) } 
                else { val nameToken = expect(TokenType4.IDENTIFIER); if (peek().type == TokenType4.COLON) { advance(); advance() }; expect(TokenType4.ASSIGN); Stmt4.Assignment(nameToken.value, parseExpression(), nameToken.line, isDeclaration = true) }
            }
            TokenType4.IDENTIFIER -> {
                val lvalueExpr = parseExpression()
                if (peek().type == TokenType4.ASSIGN) { advance(); val rvalueExpr = parseExpression(); when (lvalueExpr) { is Expr4.VarRef -> Stmt4.Assignment(lvalueExpr.name, rvalueExpr, lvalueExpr.line, isDeclaration = false); is Expr4.IndexAccess -> Stmt4.IndexAssignment(lvalueExpr.target, lvalueExpr.index, rvalueExpr, lvalueExpr.line); else -> throw TesseractError4("Invalid assignment target", lvalueExpr.line) } } 
                else Stmt4.ExprStmt(lvalueExpr, current.line)
            }
            else -> Stmt4.ExprStmt(parseExpression(), current.line)
        }
    }

    private fun parseWhile(): Stmt4 { advance(); val cond = parseExpression(); expect(TokenType4.DO); return Stmt4.WhileStmt(cond, parseBlock(), cond.line) }
    private fun parseFor(): Stmt4 { advance(); val varName = expect(TokenType4.IDENTIFIER).value; expect(TokenType4.IN); val firstExpr = parseExpression(); if (peek().type == TokenType4.TO) { advance(); val secondExpr = parseExpression(); expect(TokenType4.DO); return Stmt4.ForRangeStmt(varName, firstExpr, secondExpr, parseBlock(), firstExpr.line) } else { expect(TokenType4.DO); return Stmt4.ForInStmt(varName, firstExpr, parseBlock(), firstExpr.line) } }
    private fun parseFunctionDef(): Stmt4 { advance(); val nameToken = expect(TokenType4.IDENTIFIER); expect(TokenType4.LPAREN); val params = mutableListOf<String>(); if (peek().type != TokenType4.RPAREN) { do { params.add(expect(TokenType4.IDENTIFIER).value); if (peek().type == TokenType4.COLON) { advance(); advance() }; if (peek().type == TokenType4.COMMA) advance() else break } while (peek().type != TokenType4.RPAREN) }; expect(TokenType4.RPAREN); if (peek().type == TokenType4.ARROW) { advance(); advance() }; return Stmt4.FunctionDef(nameToken.value, params, parseBlock(), nameToken.line) }
    private fun parseBlock(): List<Stmt4> { expect(TokenType4.LBRACE); val stmts = mutableListOf<Stmt4>(); while (peek().type != TokenType4.RBRACE && peek().type != TokenType4.EOF) { if (peek().type == TokenType4.SEPARATOR) advance() else stmts.add(parseStatement()) }; expect(TokenType4.RBRACE); return stmts }

    private fun parseExpression(): Expr4 = parseLogicalOr()
    private fun parseLogicalOr(): Expr4 { var left = parseLogicalAnd(); while (peek().type == TokenType4.OR) { val op = advance().type; left = Expr4.BinaryOp(left, op, parseLogicalAnd(), left.line) }; return left }
    private fun parseLogicalAnd(): Expr4 { var left = parsePipeline(); while (peek().type == TokenType4.AND) { val op = advance().type; left = Expr4.BinaryOp(left, op, parsePipeline(), left.line) }; return left }
    private fun parsePipeline(): Expr4 { var left = parseComparison(); while (peek().type == TokenType4.PIPE) { advance(); left = Expr4.Pipeline(left, parseComparison(), left.line) }; return left }
    private fun parseComparison(): Expr4 { var left = parseXor(); while (peek().type in listOf(TokenType4.GT, TokenType4.LT, TokenType4.GTE, TokenType4.LTE, TokenType4.EQ, TokenType4.NEQ)) { val op = advance().type; left = Expr4.BinaryOp(left, op, parseXor(), left.line) }; return left }
    private fun parseXor(): Expr4 { var left = parseAddition(); while (peek().type == TokenType4.XOR) { left = Expr4.BinaryOp(left, advance().type, parseAddition(), left.line) }; return left }
    private fun parseAddition(): Expr4 { var left = parseMultiplication(); while (peek().type == TokenType4.PLUS || peek().type == TokenType4.MINUS) { left = Expr4.BinaryOp(left, advance().type, parseMultiplication(), left.line) }; return left }
    private fun parseMultiplication(): Expr4 { var left = parseExponentiation(); while (peek().type in listOf(TokenType4.MUL, TokenType4.DIV, TokenType4.INT_DIV, TokenType4.MOD)) { left = Expr4.BinaryOp(left, advance().type, parseExponentiation(), left.line) }; return left }
    private fun parseExponentiation(): Expr4 { val base = parseUnary(); return if (peek().type == TokenType4.POW) { advance(); Expr4.BinaryOp(base, TokenType4.POW, parseExponentiation(), base.line) } else base }
    private fun parseUnary(): Expr4 = if (peek().type == TokenType4.MINUS || peek().type == TokenType4.PLUS || peek().type == TokenType4.NEGATE) Expr4.UnaryOp(advance().type, parseUnary(), peek().line) else parsePrimary()
    
    private fun parsePrimary(): Expr4 {
        val token = peek()
        var expr: Expr4 = when (token.type) {
            TokenType4.NUMBER -> { 
                advance()
                if (token.value.endsWith("i")) { val re = 0.0; val im = token.value.dropLast(1).toDoubleOrNull() ?: 1.0; Expr4.ComplexLit(re, im, token.line) } 
                else { try { Expr4.IntLit(token.value.toLong(), token.line) } catch (e: NumberFormatException) { try { Expr4.NumLit(token.value.toDouble(), token.line) } catch (e2: NumberFormatException) { try { Expr4.BigIntLit(BigInteger(token.value), token.line) } catch (e3: Exception) { throw TesseractError4("Invalid number format", token.line) } } } }
            }
            TokenType4.STRING -> { advance(); Expr4.StrLit(token.value, token.line) }
            TokenType4.IF -> { advance(); val cond = parseExpression(); expect(TokenType4.THEN); val thenExpr = if (peek().type == TokenType4.LBRACE) Expr4.BlockExpr(parseBlock(), peek().line) else parseExpression(); expect(TokenType4.ELSE); val elseExpr = if (peek().type == TokenType4.LBRACE) Expr4.BlockExpr(parseBlock(), peek().line) else parseExpression(); Expr4.IfElse(cond, thenExpr, elseExpr, token.line) }
            TokenType4.FN -> { advance(); val params = mutableListOf<String>(); if (peek().type == TokenType4.LPAREN) { advance(); if (peek().type != TokenType4.RPAREN) { do { params.add(expect(TokenType4.IDENTIFIER).value); if (peek().type == TokenType4.COMMA) advance() else break } while (peek().type != TokenType4.RPAREN) }; expect(TokenType4.RPAREN) }; expect(TokenType4.LBRACE); val body = mutableListOf<Stmt4>(); while (peek().type != TokenType4.RBRACE && peek().type != TokenType4.EOF) { if (peek().type == TokenType4.SEPARATOR) advance() else body.add(parseStatement()) }; expect(TokenType4.RBRACE); Expr4.AnonymousFunc(params, body, token.line) }
            TokenType4.RETURN -> { advance(); val hasValue = peek().type != TokenType4.RBRACE && peek().type != TokenType4.RBRACKET && peek().type != TokenType4.COMMA && peek().type != TokenType4.EOF && peek().type != TokenType4.COLON && peek().type != TokenType4.THEN && peek().type != TokenType4.ELSE && peek().type != TokenType4.DO && peek().type != TokenType4.SEPARATOR; val value = if (hasValue) parseExpression() else null; Expr4.ReturnExpr(value, token.line) }
            TokenType4.IDENTIFIER -> { advance(); if (peek().type == TokenType4.LPAREN) { advance(); val args = mutableListOf<Expr4>(); if (peek().type != TokenType4.RPAREN) { args.add(parseExpression()); while (peek().type == TokenType4.COMMA) { advance(); args.add(parseExpression()) } }; expect(TokenType4.RPAREN); Expr4.FuncCall(token.value, args, token.line) } else Expr4.VarRef(token.value, token.line) }
            TokenType4.LPAREN -> { advance(); val e = parseExpression(); expect(TokenType4.RPAREN); e }
            TokenType4.LBRACKET -> { advance(); val elements = mutableListOf<Expr4>(); if (peek().type != TokenType4.RBRACKET) { elements.add(parseExpression()); while (peek().type == TokenType4.COMMA) { advance(); elements.add(parseExpression()) } }; expect(TokenType4.RBRACKET); Expr4.ArrayLit(elements, token.line) }
            else -> throw TesseractError4("Unexpected token: ${token.value}", token.line)
        }
        while (peek().type == TokenType4.LBRACKET || peek().type == TokenType4.DOT) {
            if (peek().type == TokenType4.LBRACKET) { val bracketLine = peek().line; advance(); val indexExpr = parseExpression(); expect(TokenType4.RBRACKET); expr = Expr4.IndexAccess(expr, indexExpr, bracketLine) } 
            else if (peek().type == TokenType4.DOT) { advance(); val methodName = expect(TokenType4.IDENTIFIER).value; expect(TokenType4.LPAREN); val args = mutableListOf<Expr4>(); if (peek().type != TokenType4.RPAREN) { args.add(parseExpression()); while (peek().type == TokenType4.COMMA) { advance(); args.add(parseExpression()) } }; expect(TokenType4.RPAREN); expr = Expr4.MethodCall(expr, methodName, args, expr.line) }
        }
        return expr
    }
}

// ============================================================================
// TESSERACT ENGINE 4: EVALUATOR
// ============================================================================

class Environment4(private val parent: Environment4? = null) {
    private val values = mutableMapOf<String, TValue4>()
    fun get(name: String): TValue4? = values[name] ?: parent?.get(name)
    fun has(name: String): Boolean = values.containsKey(name) || (parent?.has(name) ?: false)
    fun set(name: String, value: TValue4) { if (has(name)) { if (values.containsKey(name)) values[name] = value else parent?.set(name, value) } else values[name] = value }
    fun declare(name: String, value: TValue4) { values[name] = value }
    fun createChild(): Environment4 = Environment4(this)
}

class Evaluator4(private val context: Context) {
    private var env = Environment4(); private val userFunctions = mutableMapOf<String, Stmt4.FunctionDef>()
    private val callStack = mutableListOf<String>(); private val results = mutableListOf<String>()
    private var recursionDepth = 0; private var totalUserFunctionCalls = 0L 
    private val secureRandom = SecureRandom(); private val standardRandom = Random()

    private fun resetEnvironment() {
        env = Environment4(); userFunctions.clear(); recursionDepth = 0; totalUserFunctionCalls = 0L; results.clear()
        env.set("PI", TValue4.TNum(PI)); env.set("E", TValue4.TNum(E)); env.set("PHI", TValue4.TNum(1.618033988749895))
        env.set("TRUE", TValue4.TBool(true)); env.set("FALSE", TValue4.TBool(false)); env.set("NULL", TValue4.TNull)
        env.set("true", TValue4.TBool(true)); env.set("false", TValue4.TBool(false)); env.set("null", TValue4.TNull)
        env.set("I", TValue4.TComplex(0.0, 1.0)); env.set("i", TValue4.TComplex(0.0, 1.0))
    }

    fun evaluate(statements: List<Stmt4>, constantOverrides: Map<String, String>): String {
        resetEnvironment()
        constantOverrides.forEach { (key, value) -> env.set(key, if (value.contains('.')) TValue4.TNum(value.toDoubleOrNull() ?: 0.0) else TValue4.TInt(value.toLongOrNull() ?: 0L)) }
        for (stmt in statements) { if (stmt is Stmt4.FunctionDef) userFunctions[stmt.name] = stmt }
        var exitDelayMs: Long? = null
        try {
            for (stmt in statements) {
                when (stmt) {
                    is Stmt4.SeparatorStmt -> results.add("---")
                    is Stmt4.FunctionDef -> {}
                    is Stmt4.Assignment -> { if (!constantOverrides.containsKey(stmt.name)) env.set(stmt.name, eval(stmt.value)) }
                    else -> { val res = evalStmt(stmt); if (res != null && res !is TValue4.TNull) results.add(res.displayString()) }
                }
            }
        } catch (e: ReturnValue4) { if (e.value != null && e.value !is TValue4.TNull) results.add(e.value.displayString()) } 
        catch (e: TesseractExitCommand4) { exitDelayMs = e.delayMs } 
        val output = if (results.isEmpty()) "void" else results.joinToString("\n")
        return if (exitDelayMs != null) "__TESSERACT_EXIT__:$exitDelayMs\n$output" else "Success:\n$output"
    }

    private fun evalStmt(node: Stmt4): TValue4? {
        return when (node) {
            is Stmt4.AssertStmt -> { 
                val condVal = eval(node.condition)
                if (!condVal.toBoolean()) {
                    val msg = if (node.message != null) {
                        try { eval(node.message).displayString() } catch (e: Exception) { "Error evaluating message" }
                    } else {
                        "Condition evaluated to false"
                    }
                    throw TesseractError4("Assertion failed: $msg", node.line, callStack.toList())
                }
                null 
            }
            is Stmt4.ReturnStmt -> throw ReturnValue4(if (node.value != null) eval(node.value) else null)
            is Stmt4.Assignment -> { val evaluatedValue = eval(node.value); if (node.isDeclaration) env.declare(node.name, evaluatedValue) else env.set(node.name, evaluatedValue); null }
            is Stmt4.DestructuringAssignment -> { val value = eval(node.value); if (value is TValue4.TArray) { for (i in node.names.indices) { val valToAssign = if (i < value.items.size) value.items[i] else TValue4.TNull; if (node.isDeclaration) env.declare(node.names[i], valToAssign) else env.set(node.names[i], valToAssign) } } else throw TesseractError4("Can only destructure arrays", node.line); null }
            is Stmt4.IndexAssignment -> { val target = eval(node.target); val indexVal = eval(node.index); val value = eval(node.value); if (target is TValue4.TArray) { if (indexVal is TValue4.TStr) target.fields[indexVal.value] = value else { val idx = indexVal.toLong(node.line).toInt(); val actual = if (idx < 0) target.items.size + idx else idx; target.items[actual] = value } }; null }
            is Stmt4.ExitStmt -> throw TesseractExitCommand4(node.delayMs)
            is Stmt4.WhileStmt -> { var iters = 0; val start = System.currentTimeMillis(); while (true) { if (System.currentTimeMillis() - start > 3000) throw TesseractError4("Timeout", node.line); if (!eval(node.cond).toBoolean()) break; for (s in node.body) evalStmt(s); if (++iters > 1_000_000) throw TesseractError4("Loop limit", node.line) }; null }
            is Stmt4.ForRangeStmt -> { var iters = 0; val start = System.currentTimeMillis(); val s = eval(node.start).toLong(node.line); val e = eval(node.end).toLong(node.line); val step = if (s <= e) 1L else -1L; var i = s; while (if (step > 0) i <= e else i >= e) { if (System.currentTimeMillis() - start > 3000) throw TesseractError4("Timeout", node.line); env.declare(node.varName, TValue4.TInt(i)); for (st in node.body) evalStmt(st); i += step; if (++iters > 1_000_000) throw TesseractError4("Loop limit", node.line) }; null }
            is Stmt4.ForInStmt -> { var iters = 0; val start = System.currentTimeMillis(); val col = eval(node.collection); val items = if (col is TValue4.TArray) col.items else if (col is TValue4.TStr) col.value.map { TValue4.TStr(it.toString()) } else throw TesseractError4("Not iterable", node.line); for (item in items) { if (System.currentTimeMillis() - start > 3000) throw TesseractError4("Timeout", node.line); env.declare(node.varName, item); for (st in node.body) evalStmt(st); if (++iters > 1_000_000) throw TesseractError4("Loop limit", node.line) }; null }
            is Stmt4.ExprStmt -> eval(node.expr); is Stmt4.FunctionDef -> { env.declare(node.name, TValue4.TFunction(node.params, node.body, env)); null }; is Stmt4.SeparatorStmt -> null
        }
    }

    private fun eval(node: Expr4): TValue4 {
        return when (node) {
            is Expr4.NumLit -> TValue4.TNum(node.value); is Expr4.IntLit -> TValue4.TInt(node.value); is Expr4.BigIntLit -> TValue4.TBigInt(node.value)
            is Expr4.ComplexLit -> TValue4.TComplex(node.re, node.im); is Expr4.StrLit -> TValue4.TStr(node.value)
            is Expr4.VarRef -> env.get(node.name) ?: userFunctions[node.name]?.let { TValue4.TFunction(it.params, it.body, env) } ?: throw TesseractError4("Undefined: ${node.name}", node.line)
            is Expr4.UnaryOp -> { 
                val v = eval(node.operand)
                if (node.op == TokenType4.MINUS) { when (v) { is TValue4.TNum -> TValue4.TNum(-v.value); is TValue4.TInt -> TValue4.TInt(-v.value); is TValue4.TBigInt -> TValue4.TBigInt(-v.value); is TValue4.TComplex -> TValue4.TComplex(-v.re, -v.im); is TValue4.TRational -> TValue4.TRational(-v.num, v.den); else -> throw TesseractError4("Unary minus type error", node.line) } } 
                else if (node.op == TokenType4.NEGATE) TValue4.TBool(!v.toBoolean()) else v
            }
            is Expr4.IfElse -> if (eval(node.cond).toBoolean()) eval(node.thenExpr) else eval(node.elseExpr)
            is Expr4.BinaryOp -> evalBinaryOp(node)
            is Expr4.Pipeline -> { val l = eval(node.left); val temp = when (l) { is TValue4.TNum -> Expr4.NumLit(l.value, node.line); is TValue4.TInt -> Expr4.IntLit(l.value, node.line); is TValue4.TStr -> Expr4.StrLit(l.value, node.line); else -> throw TesseractError4("Pipeline type error", node.line) }; if (node.right is Expr4.FuncCall) eval(Expr4.FuncCall(node.right.name, listOf(temp) + node.right.args, node.line)) else throw TesseractError4("Pipeline expects function", node.line) }
            is Expr4.FuncCall -> evalFuncCall(node)
            is Expr4.ArrayLit -> TValue4.TArray(node.elements.map { eval(it) }.toMutableList())
            is Expr4.AnonymousFunc -> TValue4.TFunction(node.params, node.body, env)
            is Expr4.ReturnExpr -> throw ReturnValue4(if (node.value != null) eval(node.value) else null)
            is Expr4.BlockExpr -> { var res: TValue4? = null; for (stmt in node.statements) res = evalStmt(stmt); res ?: TValue4.TNull }
            is Expr4.IndexAccess -> { val t = eval(node.target); val i = eval(node.index); if (t is TValue4.TArray) { if (i is TValue4.TStr) t.fields[i.value] ?: TValue4.TNull else { val idx = i.toLong(node.line).toInt(); val actual = if (idx < 0) t.items.size + idx else idx; t.items[actual] } } else if (t is TValue4.TStr) { val idx = i.toLong(node.line).toInt(); val actual = if (idx < 0) t.value.length + idx else idx; TValue4.TStr(t.value[actual].toString()) } else throw TesseractError4("Cannot index", node.line) }
            is Expr4.MethodCall -> throw TesseractError4("Methods not fully supported in E4", node.line)
        }
    }

    private fun getRationalParts(v: TValue4): Pair<BigInteger, BigInteger>? = when(v) {
        is TValue4.TInt -> Pair(BigInteger.valueOf(v.value), BigInteger.ONE)
        is TValue4.TBigInt -> Pair(v.value, BigInteger.ONE)
        is TValue4.TRational -> Pair(v.num, v.den)
        is TValue4.TNum -> {
            if (v.value.isNaN() || v.value.isInfinite()) null
            else if (v.value % 1.0 == 0.0 && abs(v.value) < 1e15) Pair(BigInteger.valueOf(v.value.toLong()), BigInteger.ONE)
            else {
                val bd = BigDecimal.valueOf(v.value)
                Pair(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale()))
            }
        }
        else -> null
    }

    private fun valuesEqual(l: TValue4, r: TValue4, line: Int): Boolean {
        if (l is TValue4.TComplex && r !is TValue4.TComplex) {
            if (l.im != 0.0) return false
            return valuesEqual(TValue4.TNum(l.re), r, line)
        }
        if (r is TValue4.TComplex && l !is TValue4.TComplex) {
            if (r.im != 0.0) return false
            return valuesEqual(l, TValue4.TNum(r.re), line)
        }

        val ratL = getRationalParts(l)
        val ratR = getRationalParts(r)
        if (ratL != null && ratR != null) {
            return ratL.first * ratR.second == ratR.first * ratL.second
        }

        if (l::class != r::class) return false
        
        return when (l) {
            is TValue4.TStr -> l.value == (r as TValue4.TStr).value
            is TValue4.TBool -> l.value == (r as TValue4.TBool).value
            is TValue4.TNull -> true
            is TValue4.TComplex -> {
                val rc = r as TValue4.TComplex
                l.re == rc.re && l.im == rc.im
            }
            is TValue4.TMatrix -> {
                val rm = r as TValue4.TMatrix
                l.rows == rm.rows && l.cols == rm.cols && l.data.contentEquals(rm.data)
            }
            is TValue4.TArray -> {
                val ra = r as TValue4.TArray
                if (l.items.size != ra.items.size) false
                else l.items.indices.all { valuesEqual(l.items[it], ra.items[it], line) }
            }
            is TValue4.TFunction -> false 
            is TValue4.TPoly -> {
                val rp = r as TValue4.TPoly
                l.coeffs == rp.coeffs
            }
            else -> false 
        }
    }

    private fun evalBinaryOp(node: Expr4.BinaryOp): TValue4 {
        val left = eval(node.left); val right = eval(node.right)
        
        if (node.op == TokenType4.PLUS && (left is TValue4.TStr || right is TValue4.TStr)) {
            return TValue4.TStr(left.displayString() + right.displayString())
        }

        if (node.op == TokenType4.AND) return TValue4.TBool(left.toBoolean() && right.toBoolean())
        if (node.op == TokenType4.OR) return TValue4.TBool(left.toBoolean() || right.toBoolean())
        
        if (node.op in listOf(TokenType4.GT, TokenType4.LT, TokenType4.GTE, TokenType4.LTE, TokenType4.EQ, TokenType4.NEQ)) {
            val res = when (node.op) {
                TokenType4.EQ -> valuesEqual(left, right, node.line)
                TokenType4.NEQ -> !valuesEqual(left, right, node.line)
                TokenType4.GT -> left.toDouble(node.line) > right.toDouble(node.line)
                TokenType4.LT -> left.toDouble(node.line) < right.toDouble(node.line)
                TokenType4.GTE -> left.toDouble(node.line) >= right.toDouble(node.line)
                TokenType4.LTE -> left.toDouble(node.line) <= right.toDouble(node.line)
                else -> false
            }
            return TValue4.TBool(res)
        }

        if (left is TValue4.TRational || right is TValue4.TRational) {
            fun toRat(v: TValue4): TValue4.TRational {
                return when (v) {
                    is TValue4.TRational -> v
                    is TValue4.TInt -> TValue4.TRational(BigInteger.valueOf(v.value), BigInteger.ONE)
                    is TValue4.TBigInt -> TValue4.TRational(v.value, BigInteger.ONE)
                    is TValue4.TNum -> {
                        if (v.value % 1.0 == 0.0) TValue4.TRational(BigInteger.valueOf(v.value.toLong()), BigInteger.ONE)
                        else {
                            val bd = BigDecimal.valueOf(v.value)
                            val num = bd.unscaledValue()
                            val den = BigInteger.TEN.pow(bd.scale())
                            TValue4.TRational(num, den)
                        }
                    }
                    else -> throw TesseractError4("Cannot convert ${v::class.simpleName} to rational", node.line)
                }
            }
            val l = toRat(left)
            val r = toRat(right)
            
            fun simplify(num: BigInteger, den: BigInteger): TValue4.TRational {
                if (den == BigInteger.ZERO) throw TesseractError4("Division by zero in rational", node.line)
                val g = num.gcd(den)
                val n = num / g
                val d = den / g
                return if (d < BigInteger.ZERO) TValue4.TRational(-n, -d) else TValue4.TRational(n, d)
            }

            return when (node.op) {
                TokenType4.PLUS -> simplify(l.num * r.den + r.num * l.den, l.den * r.den)
                TokenType4.MINUS -> simplify(l.num * r.den - r.num * l.den, l.den * r.den)
                TokenType4.MUL -> simplify(l.num * r.num, l.den * r.den)
                TokenType4.DIV -> { MathGuard4.checkDivision(right, node.line); simplify(l.num * r.den, l.den * r.num) }
                else -> throw TesseractError4("Rational operator error", node.line)
            }
        }

        if (left is TValue4.TBigInt || right is TValue4.TBigInt) {
            fun toBig(v: TValue4): BigInteger {
                return when (v) {
                    is TValue4.TBigInt -> v.value
                    is TValue4.TInt -> BigInteger.valueOf(v.value)
                    is TValue4.TNum -> if (v.value % 1.0 == 0.0) BigInteger.valueOf(v.value.toLong()) else throw TesseractError4("Cannot convert fractional number to BigInt", node.line)
                    is TValue4.TRational -> if (v.den == BigInteger.ONE) v.num else throw TesseractError4("Cannot convert fractional rational to BigInt", node.line)
                    else -> throw TesseractError4("Cannot convert ${v::class.simpleName} to BigInt", node.line)
                }
            }
            val l = toBig(left)
            val r = toBig(right)
            return when (node.op) {
                TokenType4.PLUS -> TValue4.TBigInt(l + r); TokenType4.MINUS -> TValue4.TBigInt(l - r); TokenType4.MUL -> TValue4.TBigInt(l * r)
                TokenType4.DIV -> { MathGuard4.checkDivision(right, node.line); TValue4.TBigInt(l / r) }
                TokenType4.MOD -> { MathGuard4.checkDivision(right, node.line); TValue4.TBigInt(l % r) }
                TokenType4.POW -> TValue4.TBigInt(l.pow(r.toInt()))
                else -> throw TesseractError4("BigInt operator error", node.line)
            }
        }

        if (left is TValue4.TComplex || right is TValue4.TComplex) {
            val l = if (left is TValue4.TComplex) left else TValue4.TComplex(left.toDouble(node.line), 0.0)
            val r = if (right is TValue4.TComplex) right else TValue4.TComplex(right.toDouble(node.line), 0.0)
            return when (node.op) {
                TokenType4.PLUS -> TValue4.TComplex(l.re + r.re, l.im + r.im)
                TokenType4.MINUS -> TValue4.TComplex(l.re - r.re, l.im - r.im)
                TokenType4.MUL -> TValue4.TComplex(l.re * r.re - l.im * r.im, l.re * r.im + l.im * r.re)
                TokenType4.DIV -> { val den = r.re * r.re + r.im * r.im; TValue4.TComplex((l.re * r.re + l.im * r.im) / den, (l.im * r.re - l.re * r.im) / den) }
                TokenType4.POW -> { val r2 = r.re; val i2 = r.im; val lnR = 0.5 * ln(l.re * l.re + l.im * l.im); val theta = atan2(l.im, l.re); val mag = exp(r2 * lnR - i2 * theta); val arg = r2 * theta + i2 * lnR; TValue4.TComplex(mag * cos(arg), mag * sin(arg)) }
                else -> throw TesseractError4("Complex operator error", node.line)
            }
        }

        if (left is TValue4.TMatrix && right is TValue4.TMatrix) {
            if (node.op == TokenType4.MUL) {
                if (left.cols != right.rows) throw TesseractError4("Matrix dimension mismatch", node.line)
                val res = DoubleArray(left.rows * right.cols)
                for (i in 0 until left.rows) for (j in 0 until right.cols) { var sum = 0.0; for (k in 0 until left.cols) sum += left.data[i * left.cols + k] * right.data[k * right.cols + j]; res[i * right.cols + j] = sum }
                return TValue4.TMatrix(left.rows, right.cols, res)
            }
        }
        if (left is TValue4.TMatrix && (right is TValue4.TNum || right is TValue4.TInt || right is TValue4.TBigInt)) {
            if (node.op == TokenType4.MUL) {
                val scalar = right.toDouble(node.line)
                val res = DoubleArray(left.data.size) { left.data[it] * scalar }
                return TValue4.TMatrix(left.rows, left.cols, res)
            }
        }
        if ((left is TValue4.TNum || left is TValue4.TInt || left is TValue4.TBigInt) && right is TValue4.TMatrix) {
            if (node.op == TokenType4.MUL) {
                val scalar = left.toDouble(node.line)
                val res = DoubleArray(right.data.size) { right.data[it] * scalar }
                return TValue4.TMatrix(right.rows, right.cols, res)
            }
        }
        if (left is TValue4.TMatrix && right is TValue4.TArray) {
            if (node.op == TokenType4.MUL && left.cols == right.items.size) {
                val res = mutableListOf<TValue4>(); for (i in 0 until left.rows) { var sum = 0.0; for (k in 0 until left.cols) sum += left.data[i * left.cols + k] * right.items[k].toDouble(node.line); res.add(TValue4.TNum(sum)) }; return TValue4.TArray(res)
            }
        }

        return when (node.op) {
            // ИСПРАВЛЕНИЕ 2: Добавлена поддержка конкатенации массивов через оператор +
            TokenType4.PLUS -> {
                if (left is TValue4.TStr || right is TValue4.TStr) {
                    TValue4.TStr(left.displayString() + right.displayString())
                } else if (left is TValue4.TArray && right is TValue4.TArray) {
                    TValue4.TArray((left.items + right.items).toMutableList())
                } else if (left is TValue4.TInt && right is TValue4.TInt) {
                    TValue4.TInt(left.value + right.value)
                } else {
                    TValue4.TNum(left.toDouble(node.line) + right.toDouble(node.line))
                }
            }
            TokenType4.MINUS -> if (left is TValue4.TInt && right is TValue4.TInt) TValue4.TInt(left.value - right.value) else TValue4.TNum(left.toDouble(node.line) - right.toDouble(node.line))
            TokenType4.MUL -> if (left is TValue4.TInt && right is TValue4.TInt) TValue4.TInt(left.value * right.value) else TValue4.TNum(left.toDouble(node.line) * right.toDouble(node.line))
            TokenType4.DIV -> { MathGuard4.checkDivision(right, node.line); TValue4.TNum(left.toDouble(node.line) / right.toDouble(node.line)) }
            TokenType4.INT_DIV -> { MathGuard4.checkDivision(right, node.line); TValue4.TInt(left.toLong(node.line) / right.toLong(node.line)) }
            TokenType4.MOD -> { MathGuard4.checkDivision(right, node.line); if (left is TValue4.TInt && right is TValue4.TInt) TValue4.TInt(left.value % right.value) else TValue4.TNum(left.toDouble(node.line) % right.toDouble(node.line)) }
            TokenType4.POW -> { 
                if (left is TValue4.TInt && right is TValue4.TInt && right.value >= 0) { try { TValue4.TBigInt(BigInteger.valueOf(left.value).pow(right.value.toInt())) } catch (e: Exception) { TValue4.TNum(left.value.toDouble().pow(right.value.toDouble())) } } 
                else { val r = left.toDouble(node.line).pow(right.toDouble(node.line)); MathGuard4.checkOverflow(r, node.line); TValue4.TNum(r) }
            }
            TokenType4.XOR -> { if (left is TValue4.TInt && right is TValue4.TInt) TValue4.TInt(left.value xor right.value) else throw TesseractError4("XOR requires ints", node.line) }
            else -> throw TesseractError4("Unknown operator", node.line)
        }
    }

    private fun solveGauss(a: Array<DoubleArray>, b: DoubleArray, line: Int): DoubleArray {
        val n = a.size; val aug = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (i in 0 until n) {
            var maxEl = abs(aug[i][i]); var maxRow = i
            for (k in i + 1 until n) if (abs(aug[k][i]) > maxEl) { maxEl = abs(aug[k][i]); maxRow = k }
            val temp = aug[maxRow]; aug[maxRow] = aug[i]; aug[i] = temp
            if (abs(aug[i][i]) < 1e-12) throw TesseractError4("Matrix is singular", line)
            for (k in i + 1 until n) { val c = -aug[k][i] / aug[i][i]; for (j in i until n + 1) { if (i == j) aug[k][j] = 0.0 else aug[k][j] += c * aug[i][j] } }
        }
        val x = DoubleArray(n); for (i in n - 1 downTo 0) { var sum = 0.0; for (j in i + 1 until n) sum += aug[i][j] * x[j]; x[i] = (aug[i][n] - sum) / aug[i][i] }
        return x
    }

    private fun evalFuncCall(node: Expr4.FuncCall): TValue4 {
        val funcVal = env.get(node.name)
        if (funcVal is TValue4.TFunction) return callTFunction(funcVal, node.args.map { eval(it) }, node.line)
        val userFunc = userFunctions[node.name]
        if (userFunc != null) {
            totalUserFunctionCalls++; if (totalUserFunctionCalls > 1_000_000) throw TesseractError4("Call limit", node.line)
            if (++recursionDepth > 2000) throw TesseractError4("Recursion depth", node.line)
            callStack.add("${node.name}()"); if (node.args.size != userFunc.params.size) throw TesseractError4("Args mismatch", node.line)
            val localEnv = env.createChild(); val oldEnv = env; env = localEnv
            for (i in userFunc.params.indices) env.declare(userFunc.params[i], eval(node.args[i]))
            val result = try { var res: TValue4? = null; for (stmt in userFunc.body) res = evalStmt(stmt); res ?: TValue4.TInt(0) } catch (e: ReturnValue4) { e.value ?: TValue4.TInt(0) } finally { env = oldEnv; callStack.removeLast(); recursionDepth-- }
            return result
        }
        callStack.add("${node.name}()")
        val args = node.args.map { eval(it) }
        val result = try {
            when (node.name) {
                "print" -> { results.add(args.joinToString(" ") { it.displayString() }); TValue4.TNull }
                
                "real", "re" -> TValue4.TNum((args[0] as? TValue4.TComplex)?.re ?: throw TesseractError4("re requires complex", node.line))
                "imag", "im" -> TValue4.TNum((args[0] as? TValue4.TComplex)?.im ?: throw TesseractError4("im requires complex", node.line))
                
                "inverse", "inv" -> {
                    val m = args[0] as? TValue4.TMatrix ?: throw TesseractError4("inv requires matrix", node.line)
                    if (m.rows != m.cols) throw TesseractError4("Inv requires square matrix", node.line)
                    val n = m.rows
                    val aug = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m.data[i * n + j] else if (j - n == i) 1.0 else 0.0 } }
                    for (i in 0 until n) {
                        var maxEl = abs(aug[i][i]); var maxRow = i
                        for (k in i + 1 until n) if (abs(aug[k][i]) > maxEl) { maxEl = abs(aug[k][i]); maxRow = k }
                        val temp = aug[maxRow]; aug[maxRow] = aug[i]; aug[i] = temp
                        if (abs(aug[i][i]) < 1e-12) throw TesseractError4("Matrix is singular", node.line)
                        val div = aug[i][i]
                        for (j in 0 until 2 * n) aug[i][j] /= div
                        for (k in 0 until n) {
                            if (k != i) {
                                val c = aug[k][i]
                                for (j in 0 until 2 * n) aug[k][j] -= c * aug[i][j]
                            }
                        }
                    }
                    val res = DoubleArray(n * n)
                    for (i in 0 until n) for (j in 0 until n) res[i * n + j] = aug[i][n + j]
                    TValue4.TMatrix(n, n, res)
                }
                
                "derivative", "diff" -> {
                    val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("diff requires poly", node.line)
                    if (p.coeffs.size <= 1) TValue4.TPoly(listOf(0.0)) else TValue4.TPoly(p.coeffs.drop(1).mapIndexed { i, c -> c * (i + 1) })
                }
                
                "antiderivative", "integrate" -> {
                    val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("integrate requires poly", node.line)
                    TValue4.TPoly(listOf(0.0) + p.coeffs.mapIndexed { i, c -> c / (i + 1) })
                }
                
                "expand" -> { val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("expand requires poly", node.line); p }
                "simplify" -> { val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("simplify requires poly", node.line); TValue4.TPoly(p.coeffs.filter { it != 0.0 }.ifEmpty { listOf(0.0) }) }
                
                "solve_poly" -> {
                    val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("solve_poly requires poly", node.line)
                    when (p.coeffs.size) {
                        0 -> TValue4.TArray(mutableListOf())
                        1 -> TValue4.TArray(mutableListOf())
                        2 -> {
                            val a = p.coeffs[1]; val b = p.coeffs[0]
                            if (abs(a) < 1e-15) throw TesseractError4("Not a linear equation", node.line)
                            TValue4.TArray(mutableListOf(TValue4.TNum(-b / a)))
                        }
                        3 -> {
                            val a = p.coeffs[2]; val b = p.coeffs[1]; val c = p.coeffs[0]
                            val D = b * b - 4 * a * c
                            when {
                                D > 1e-12 -> TValue4.TArray(mutableListOf(
                                    TValue4.TNum((-b + sqrt(D)) / (2 * a)),
                                    TValue4.TNum((-b - sqrt(D)) / (2 * a))
                                ))
                                D >= -1e-12 -> TValue4.TArray(mutableListOf(TValue4.TNum(-b / (2 * a))))
                                else -> TValue4.TArray(mutableListOf(
                                    TValue4.TComplex(-b / (2 * a), sqrt(-D) / (2 * a)),
                                    TValue4.TComplex(-b / (2 * a), -sqrt(-D) / (2 * a))
                                ))
                            }
                        }
                        else -> throw TesseractError4("solve_poly supports degree 1 and 2 only", node.line)
                    }
                }
                
                "rational" -> {
                    val num = BigInteger.valueOf(args[0].toLong(node.line))
                    val den = BigInteger.valueOf(args[1].toLong(node.line))
                    if (den == BigInteger.ZERO) throw TesseractError4("Division by zero in rational", node.line)
                    val g = num.gcd(den)
                    var n = num / g
                    var d = den / g
                    if (d < BigInteger.ZERO) { n = -n; d = -d }
                    TValue4.TRational(n, d)
                }
                
                "matrix" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("matrix requires array", node.line)
                    if (arr.items.isEmpty()) throw TesseractError4("matrix requires non-empty array", node.line)
                    val rows = arr.items.size
                    val firstRow = arr.items[0] as? TValue4.TArray ?: throw TesseractError4("matrix requires 2D array", node.line)
                    val cols = firstRow.items.size
                    if (cols == 0) throw TesseractError4("matrix rows cannot be empty", node.line)
                    val data = DoubleArray(rows * cols)
                    for (i in 0 until rows) {
                        val row = arr.items[i] as? TValue4.TArray ?: throw TesseractError4("matrix requires 2D array (row $i is not array)", node.line)
                        if (row.items.size != cols) throw TesseractError4("matrix row $i has ${row.items.size} elements, expected $cols", node.line)
                        for (j in 0 until cols) {
                            data[i * cols + j] = row.items[j].toDouble(node.line)
                        }
                    }
                    TValue4.TMatrix(rows, cols, data)
                }
                
                "zeros" -> TValue4.TMatrix(args[0].toLong(node.line).toInt(), args[1].toLong(node.line).toInt(), DoubleArray(args[0].toLong(node.line).toInt() * args[1].toLong(node.line).toInt()))
                
                "ones" -> {
                    val r = args[0].toLong(node.line).toInt()
                    val c = args[1].toLong(node.line).toInt()
                    val d = DoubleArray(r * c) { 1.0 }
                    TValue4.TMatrix(r, c, d)
                }
                
                "identity" -> {
                    val n = args[0].toLong(node.line).toInt()
                    val d = DoubleArray(n * n)
                    for (i in 0 until n) d[i * n + i] = 1.0
                    TValue4.TMatrix(n, n, d)
                }
                
                "transpose" -> {
                    val m = args[0] as? TValue4.TMatrix ?: throw TesseractError4("transpose requires matrix", node.line)
                    val d = DoubleArray(m.rows * m.cols)
                    for (i in 0 until m.rows) {
                        for (j in 0 until m.cols) {
                            d[j * m.rows + i] = m.data[i * m.cols + j]
                        }
                    }
                    TValue4.TMatrix(m.cols, m.rows, d)
                }
                
                "det" -> {
                    val m = args[0] as? TValue4.TMatrix ?: throw TesseractError4("det requires matrix", node.line)
                    if (m.rows != m.cols) throw TesseractError4("Det requires square matrix", node.line)
                    val n = m.rows
                    val a = Array(n) { i -> DoubleArray(n) { j -> m.data[i * n + j] } }
                    var det = 1.0
                    for (i in 0 until n) {
                        var maxEl = abs(a[i][i])
                        var maxRow = i
                        for (k in i + 1 until n) {
                            if (abs(a[k][i]) > maxEl) {
                                maxEl = abs(a[k][i])
                                maxRow = k
                            }
                        }
                        if (maxRow != i) {
                            val temp = a[i]
                            a[i] = a[maxRow]
                            a[maxRow] = temp
                            det *= -1.0
                        }
                        if (abs(a[i][i]) < 1e-12) {
                            det = 0.0
                            break
                        }
                        det *= a[i][i]
                        for (k in i + 1 until n) {
                            val c = -a[k][i] / a[i][i]
                            for (j in i + 1 until n) a[k][j] += c * a[i][j]
                        }
                    }
                    TValue4.TNum(det)
                }
                
                "solve" -> {
                    val m = args[0] as? TValue4.TMatrix ?: throw TesseractError4("solve requires matrix", node.line)
                    val bArr = args[1] as? TValue4.TArray ?: throw TesseractError4("solve requires array", node.line)
                    val a = Array(m.rows) { i -> DoubleArray(m.cols) { j -> m.data[i * m.cols + j] } }
                    val b = DoubleArray(bArr.items.size) { bArr.items[it].toDouble(node.line) }
                    val x = solveGauss(a, b, node.line)
                    TValue4.TArray(x.map { TValue4.TNum(it) }.toMutableList())
                }
                
                "dot" -> {
                    val a = args[0] as? TValue4.TArray ?: throw TesseractError4("dot requires array", node.line)
                    val b = args[1] as? TValue4.TArray ?: throw TesseractError4("dot requires array", node.line)
                    var sum = 0.0
                    for (i in 0 until a.items.size) sum += a.items[i].toDouble(node.line) * b.items[i].toDouble(node.line)
                    TValue4.TNum(sum)
                }
                
                "cross" -> {
                    val a = args[0] as? TValue4.TArray ?: throw TesseractError4("cross requires array", node.line)
                    val b = args[1] as? TValue4.TArray ?: throw TesseractError4("cross requires array", node.line)
                    if (a.items.size != 3) throw TesseractError4("cross requires vectors with exactly 3 elements", node.line)
                    if (b.items.size != 3) throw TesseractError4("cross requires vectors with exactly 3 elements", node.line)
                    val ax = a.items[0].toDouble(node.line)
                    val ay = a.items[1].toDouble(node.line)
                    val az = a.items[2].toDouble(node.line)
                    val bx = b.items[0].toDouble(node.line)
                    val by = b.items[1].toDouble(node.line)
                    val bz = b.items[2].toDouble(node.line)
                    TValue4.TArray(mutableListOf(
                        TValue4.TNum(ay * bz - az * by),
                        TValue4.TNum(az * bx - ax * bz),
                        TValue4.TNum(ax * by - ay * bx)
                    ))
                }
                
                "norm" -> {
                    val v = args[0] as? TValue4.TArray ?: throw TesseractError4("norm requires array", node.line)
                    var sum = 0.0
                    for (x in v.items) sum += x.toDouble(node.line) * x.toDouble(node.line)
                    TValue4.TNum(sqrt(sum))
                }
                
                "mean", "avg" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("mean requires array", node.line)).items
                    if (arr.isEmpty()) throw TesseractError4("Empty array", node.line)
                    TValue4.TNum(arr.sumOf { it.toDouble(node.line) } / arr.size)
                }
                
                "median" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("median requires array", node.line)).items.map { it.toDouble(node.line) }.sorted()
                    if (arr.isEmpty()) throw TesseractError4("Empty array", node.line)
                    val mid = arr.size / 2
                    TValue4.TNum(if (arr.size % 2 == 0) (arr[mid - 1] + arr[mid]) / 2.0 else arr[mid])
                }
                
                "variance" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("variance requires array", node.line)).items.map { it.toDouble(node.line) }
                    val mean = arr.sum() / arr.size
                    TValue4.TNum(arr.sumOf { (it - mean) * (it - mean) } / arr.size)
                }
                
                "std_dev" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("std_dev requires array", node.line)).items.map { it.toDouble(node.line) }
                    val mean = arr.sum() / arr.size
                    TValue4.TNum(sqrt(arr.sumOf { (it - mean) * (it - mean) } / arr.size))
                }
                
                "sort" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("sort requires array", node.line)).items
                    TValue4.TArray(arr.sortedBy { it.toDouble(node.line) }.toMutableList())
                }
                
                "sort_by" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("sort_by requires array", node.line)
                    val f = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("sort_by requires function", node.line)
                    TValue4.TArray(arr.items.sortedBy { callTFunction(f, listOf(it), node.line).toDouble(node.line) }.toMutableList())
                }
                
                "is_finite" -> {
                    val d = args[0].toDouble(node.line)
                    TValue4.TBool(!d.isInfinite() && !d.isNaN())
                }
                
                "is_integer" -> {
                    val isInt = when (val arg = args[0]) {
                        is TValue4.TInt, is TValue4.TBigInt -> true
                        is TValue4.TNum -> arg.value % 1.0 == 0.0
                        is TValue4.TRational -> arg.den == BigInteger.ONE
                        else -> false
                    }
                    TValue4.TBool(isInt)
                }
                
                "is_close" -> {
                    val diff = abs(args[0].toDouble(node.line) - args[1].toDouble(node.line))
                    val tol = if (args.size > 2) args[2].toDouble(node.line) else 1e-9
                    TValue4.TBool(diff < tol)
                }
                
                "is_prime" -> {
                    val n = args[0].toLong(node.line)
                    if (n < 2) TValue4.TBool(false)
                    else {
                        var prime = true
                        if (n < 4) prime = true
                        else if (n % 2 == 0L || n % 3 == 0L) prime = false
                        else {
                            var check_i = 5L
                            while (check_i * check_i <= n) {
                                if (n % check_i == 0L || n % (check_i + 2) == 0L) { prime = false; break }
                                check_i += 6
                            }
                        }
                        TValue4.TBool(prime)
                    }
                }
                
                "factorize" -> {
                    var num = args[0].toLong(node.line)
                    if (num < 2) throw TesseractError4("factorize requires n >= 2", node.line)
                    val factors = mutableListOf<TValue4>()
                    while (num % 2 == 0L) { factors.add(TValue4.TInt(2)); num /= 2 }
                    var d = 3L
                    while (d * d <= num) {
                        while (num % d == 0L) { factors.add(TValue4.TInt(d)); num /= d }
                        d += 2
                    }
                    if (num > 1) factors.add(TValue4.TInt(num))
                    TValue4.TArray(factors)
                }
                
                "next_prime" -> {
                    var n = args[0].toLong(node.line) + 1
                    fun isPrime(x: Long): Boolean {
                        if (x < 2) return false
                        if (x < 4) return true
                        if (x % 2 == 0L || x % 3 == 0L) return false
                        var check_i = 5L
                        while (check_i * check_i <= x) {
                            if (x % check_i == 0L || x % (check_i + 2) == 0L) return false
                            check_i += 6
                        }
                        return true
                    }
                    val startTime = System.currentTimeMillis()
                    while (!isPrime(n)) {
                        if (System.currentTimeMillis() - startTime > 2000) {
                            throw TesseractError4("next_prime: timeout (took more than 2 seconds)", node.line)
                        }
                        n++
                    }
                    TValue4.TInt(n)
                }
                
                "primes_up_to" -> {
                    val n = args[0].toLong(node.line)
                    if (n > 10_000_000L) throw TesseractError4("primes_up_to: n too large (max 10,000,000)", node.line)
                    val nInt = n.toInt()
                    if (nInt < 2) return TValue4.TArray(mutableListOf())
                    val sieve = BooleanArray(nInt + 1) { true }
                    sieve[0] = false; sieve[1] = false
                    var i = 2
                    while (i * i <= nInt) {
                        if (sieve[i]) {
                            var j = i * i
                            while (j <= nInt) { sieve[j] = false; j += i }
                        }
                        i++
                    }
                    val result = mutableListOf<TValue4>()
                    for (i in 2..nInt) if (sieve[i]) result.add(TValue4.TInt(i.toLong()))
                    TValue4.TArray(result)
                }
                
                "find_root" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("find_root requires function", node.line)
                    var a = args[1].toDouble(node.line)
                    var b = args[2].toDouble(node.line)
                    val tol = if (args.size > 3) args[3].toDouble(node.line) else 1e-7
                    var fa = callTFunction(f, listOf(TValue4.TNum(a)), node.line).toDouble(node.line)
                    val fb = callTFunction(f, listOf(TValue4.TNum(b)), node.line).toDouble(node.line)
                    
                    if (abs(fa) < tol) return TValue4.TNum(a)
                    if (abs(fb) < tol) return TValue4.TNum(b)
                    
                    if (fa * fb > 0.0) throw TesseractError4("find_root: f(a) and f(b) must have different signs", node.line)
                    
                    var resMid = (a + b) / 2.0
                    for (i in 0 until 200) {
                        val mid = (a + b) / 2.0
                        val fmid = callTFunction(f, listOf(TValue4.TNum(mid)), node.line).toDouble(node.line)
                        if (abs(fmid) < tol || (b - a) / 2 < tol) {
                            resMid = mid
                            break
                        }
                        if (fa * fmid < 0.0) {
                            b = mid
                        } else {
                            a = mid
                            fa = fmid
                        }
                    }
                    TValue4.TNum(resMid)
                }
                
                "find_root_newton" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("find_root_newton requires function", node.line)
                    var x = args[1].toDouble(node.line)
                    val tol = if (args.size > 2) args[2].toDouble(node.line) else 1e-9
                    val maxIter = if (args.size > 3) args[3].toLong(node.line).toInt() else 100
                    val h = 1e-7
                    for (i in 0 until maxIter) {
                        val fx = callTFunction(f, listOf(TValue4.TNum(x)), node.line).toDouble(node.line)
                        if (abs(fx) < tol) break
                        val fxh = callTFunction(f, listOf(TValue4.TNum(x + h)), node.line).toDouble(node.line)
                        val dfx = (fxh - fx) / h
                        if (abs(dfx) < 1e-15) throw TesseractError4("Newton: zero derivative", node.line)
                        x -= fx / dfx
                    }
                    TValue4.TNum(x)
                }

                "complex" -> TValue4.TComplex(args[0].toDouble(node.line), args[1].toDouble(node.line))
                "conj" -> { val z = args[0] as? TValue4.TComplex ?: throw TesseractError4("conj requires complex", node.line); TValue4.TComplex(z.re, -z.im) }
                "arg" -> { val z = args[0] as? TValue4.TComplex ?: throw TesseractError4("arg requires complex", node.line); TValue4.TNum(atan2(z.im, z.re)) }
                
                "abs" -> {
                    val arg = args[0]
                    if (arg is TValue4.TComplex) {
                        TValue4.TNum(sqrt(arg.re * arg.re + arg.im * arg.im))
                    } else {
                        TValue4.TNum(abs(arg.toDouble(node.line)))
                    }
                }
                
                "hypot" -> {
                    if (args.size == 1) {
                        val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("hypot(arr) requires array", node.line)
                        var sum = 0.0
                        for (x in arr.items) sum += x.toDouble(node.line) * x.toDouble(node.line)
                        TValue4.TNum(sqrt(sum))
                    } else {
                        TValue4.TNum(hypot(args[0].toDouble(node.line), args[1].toDouble(node.line)))
                    }
                }
                "atan2" -> TValue4.TNum(atan2(args[0].toDouble(node.line), args[1].toDouble(node.line)))
                "degrees" -> TValue4.TNum(args[0].toDouble(node.line) * 180.0 / PI)
                "radians" -> TValue4.TNum(args[0].toDouble(node.line) * PI / 180.0)
                "sign" -> TValue4.TNum(sign(args[0].toDouble(node.line)))
                "clamp" -> TValue4.TNum(args[0].toDouble(node.line).coerceIn(args[1].toDouble(node.line), args[2].toDouble(node.line)))
                
                "azimuth" -> {
                    val dx = args[0].toDouble(node.line)
                    val dy = args[1].toDouble(node.line)
                    var az = atan2(dx, dy) * 180 / PI
                    if (az < 0) az += 360
                    TValue4.TNum(az)
                }
                
                "bearing" -> {
                    val lat1 = args[0].toDouble(node.line) * PI / 180
                    val lon1 = args[1].toDouble(node.line) * PI / 180
                    val lat2 = args[2].toDouble(node.line) * PI / 180
                    val lon2 = args[3].toDouble(node.line) * PI / 180
                    val y = sin(lon2 - lon1) * cos(lat2)
                    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
                    var brng = atan2(y, x) * 180 / PI
                    brng = (brng + 360) % 360
                    TValue4.TNum(brng)
                }
                
                "haversine" -> {
                    val lat1 = args[0].toDouble(node.line) * PI / 180
                    val lon1 = args[1].toDouble(node.line) * PI / 180
                    val lat2 = args[2].toDouble(node.line) * PI / 180
                    val lon2 = args[3].toDouble(node.line) * PI / 180
                    val R = if (args.size > 4) args[4].toDouble(node.line) else 6371.0
                    val dlat = lat2 - lat1
                    val dlon = lon2 - lon1
                    val a = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
                    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
                    TValue4.TNum(R * c)
                }
                
                "distance_2d" -> {
                    val p1 = args[0] as? TValue4.TArray ?: throw TesseractError4("distance_2d requires array", node.line)
                    val p2 = args[1] as? TValue4.TArray ?: throw TesseractError4("distance_2d requires array", node.line)
                    if (p1.items.size < 2) throw TesseractError4("distance_2d: p1 must have at least 2 elements", node.line)
                    if (p2.items.size < 2) throw TesseractError4("distance_2d: p2 must have at least 2 elements", node.line)
                    val dx = p2.items[0].toDouble(node.line) - p1.items[0].toDouble(node.line)
                    val dy = p2.items[1].toDouble(node.line) - p1.items[1].toDouble(node.line)
                    TValue4.TNum(sqrt(dx * dx + dy * dy))
                }
                
                "distance_3d" -> {
                    val p1 = args[0] as? TValue4.TArray ?: throw TesseractError4("distance_3d requires array", node.line)
                    val p2 = args[1] as? TValue4.TArray ?: throw TesseractError4("distance_3d requires array", node.line)
                    if (p1.items.size < 3) throw TesseractError4("distance_3d: p1 must have at least 3 elements", node.line)
                    if (p2.items.size < 3) throw TesseractError4("distance_3d: p2 must have at least 3 elements", node.line)
                    val dx = p2.items[0].toDouble(node.line) - p1.items[0].toDouble(node.line)
                    val dy = p2.items[1].toDouble(node.line) - p1.items[1].toDouble(node.line)
                    val dz = p2.items[2].toDouble(node.line) - p1.items[2].toDouble(node.line)
                    TValue4.TNum(sqrt(dx * dx + dy * dy + dz * dz))
                }
                
                "integrate_num" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("integrate_num requires function", node.line)
                    val a = args[1].toDouble(node.line)
                    val b = args[2].toDouble(node.line)
                    var n = if (args.size > 3) args[3].toLong(node.line).toInt() else 100
                    if (n > 1_000_000) throw TesseractError4("integrate_num: n too large (max 1,000,000)", node.line)
                    if (n % 2 != 0) n++
                    val h = (b - a) / n
                    var sum = callTFunction(f, listOf(TValue4.TNum(a)), node.line).toDouble(node.line) + 
                              callTFunction(f, listOf(TValue4.TNum(b)), node.line).toDouble(node.line)
                    for (i in 1 until n) {
                        val x = a + i * h
                        val fx = callTFunction(f, listOf(TValue4.TNum(x)), node.line).toDouble(node.line)
                        sum += if (i % 2 == 0) 2 * fx else 4 * fx
                    }
                    TValue4.TNum(sum * h / 3)
                }
                
                "rk4" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("rk4 requires function f(t,y)", node.line)
                    val y0 = args[1].toDouble(node.line)
                    val t0 = args[2].toDouble(node.line)
                    val tEnd = args[3].toDouble(node.line)
                    val dt = if (args.size > 4) args[4].toDouble(node.line) else 0.01
                    val maxSteps = 100000
                    val estimatedSteps = ((tEnd - t0) / dt).toLong()
                    if (estimatedSteps > maxSteps) throw TesseractError4("rk4: too many steps ($estimatedSteps > $maxSteps). Increase dt or reduce range.", node.line)
                    var y = y0
                    var t = t0
                    val result = mutableListOf<TValue4>()
                    var steps = 0
                    while (t < tEnd && steps < maxSteps) {
                        val k1 = callTFunction(f, listOf(TValue4.TNum(t), TValue4.TNum(y)), node.line).toDouble(node.line)
                        val k2 = callTFunction(f, listOf(TValue4.TNum(t + dt/2), TValue4.TNum(y + dt*k1/2)), node.line).toDouble(node.line)
                        val k3 = callTFunction(f, listOf(TValue4.TNum(t + dt/2), TValue4.TNum(y + dt*k2/2)), node.line).toDouble(node.line)
                        val k4 = callTFunction(f, listOf(TValue4.TNum(t + dt), TValue4.TNum(y + dt*k3)), node.line).toDouble(node.line)
                        y += dt * (k1 + 2*k2 + 2*k3 + k4) / 6
                        t += dt
                        result.add(TValue4.TNum(y))
                        steps++
                    }
                    TValue4.TArray(result)
                }
                
                "fft" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("fft requires array", node.line)
                    val n = arr.items.size
                    if (n == 0 || (n and (n - 1)) != 0) throw TesseractError4("fft requires array size to be power of 2", node.line)
                    if (n > 1_048_576) throw TesseractError4("fft: array too large (max 2^20 = 1,048,576)", node.line)
                    
                    fun fftRec(x: List<TValue4.TComplex>): List<TValue4.TComplex> {
                        val local_n = x.size
                        if (local_n == 1) return x
                        val even = fftRec(x.filterIndexed { i, _ -> i % 2 == 0 })
                        val odd = fftRec(x.filterIndexed { i, _ -> i % 2 == 1 })
                        val result = MutableList<TValue4.TComplex>(local_n) { TValue4.TComplex(0.0, 0.0) }
                        val ang = 2 * PI / local_n
                        val wlen = TValue4.TComplex(cos(ang), sin(ang))
                        var w = TValue4.TComplex(1.0, 0.0)
                        for (i in 0 until local_n / 2) {
                            val evenVal = even[i]
                            val oddVal = odd[i]
                            val wOdd = TValue4.TComplex(w.re * oddVal.re - w.im * oddVal.im, w.re * oddVal.im + w.im * oddVal.re)
                            result[i] = TValue4.TComplex(evenVal.re + wOdd.re, evenVal.im + wOdd.im)
                            result[i + local_n/2] = TValue4.TComplex(evenVal.re - wOdd.re, evenVal.im - wOdd.im)
                            w = TValue4.TComplex(w.re * wlen.re - w.im * wlen.im, w.re * wlen.im + w.im * wlen.re)
                        }
                        return result
                    }
                    
                    val input = arr.items.map { 
                        when (it) {
                            is TValue4.TComplex -> it
                            is TValue4.TNum -> TValue4.TComplex(it.value, 0.0)
                            is TValue4.TInt -> TValue4.TComplex(it.value.toDouble(), 0.0)
                            else -> throw TesseractError4("fft requires numeric array", node.line)
                        }
                    }
                    TValue4.TArray(fftRec(input).toMutableList())
                }
                
                "ifft" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("ifft requires array", node.line)
                    val n = arr.items.size
                    if (n == 0 || (n and (n - 1)) != 0) throw TesseractError4("ifft requires array size to be power of 2", node.line)
                    if (n > 1_048_576) throw TesseractError4("ifft: array too large (max 2^20 = 1,048,576)", node.line)
                    
                    fun fftRec(x: List<TValue4.TComplex>): List<TValue4.TComplex> {
                        val local_n = x.size
                        if (local_n == 1) return x
                        val even = fftRec(x.filterIndexed { i, _ -> i % 2 == 0 })
                        val odd = fftRec(x.filterIndexed { i, _ -> i % 2 == 1 })
                        val result = MutableList<TValue4.TComplex>(local_n) { TValue4.TComplex(0.0, 0.0) }
                        val ang = -2 * PI / local_n
                        val wlen = TValue4.TComplex(cos(ang), sin(ang))
                        var w = TValue4.TComplex(1.0, 0.0)
                        for (i in 0 until local_n / 2) {
                            val evenVal = even[i]
                            val oddVal = odd[i]
                            val wOdd = TValue4.TComplex(w.re * oddVal.re - w.im * oddVal.im, w.re * oddVal.im + w.im * oddVal.re)
                            result[i] = TValue4.TComplex(evenVal.re + wOdd.re, evenVal.im + wOdd.im)
                            result[i + local_n/2] = TValue4.TComplex(evenVal.re - wOdd.re, evenVal.im - wOdd.im)
                            w = TValue4.TComplex(w.re * wlen.re - w.im * wlen.im, w.re * wlen.im + w.im * wlen.re)
                        }
                        return result
                    }
                    
                    val input = arr.items.map { 
                        when (it) {
                            is TValue4.TComplex -> it
                            is TValue4.TNum -> TValue4.TComplex(it.value, 0.0)
                            is TValue4.TInt -> TValue4.TComplex(it.value.toDouble(), 0.0)
                            else -> throw TesseractError4("ifft requires numeric array", node.line)
                        }
                    }
                    val n2 = arr.items.size
                    TValue4.TArray(fftRec(input).map { TValue4.TComplex(it.re / n2, it.im / n2) }.toMutableList())
                }
                
                "interpolate_linear" -> {
                    val points = args[0] as? TValue4.TArray ?: throw TesseractError4("interpolate_linear requires array of [x,y] pairs", node.line)
                    if (points.items.isEmpty()) throw TesseractError4("interpolate_linear: empty points array", node.line)
                    val x = args[1].toDouble(node.line)
                    val pts = points.items.map { 
                        it as? TValue4.TArray ?: throw TesseractError4("interpolate_linear: all points must be arrays", node.line)
                    }
                    for ((i, pt) in pts.withIndex()) {
                        if (pt.items.size < 2) throw TesseractError4("interpolate_linear: point $i must have at least 2 elements [x,y]", node.line)
                    }
                    val sortedPts = pts.sortedBy { it.items[0].toDouble(node.line) }
                    if (x <= sortedPts.first().items[0].toDouble(node.line)) return TValue4.TNum(sortedPts.first().items[1].toDouble(node.line))
                    if (x >= sortedPts.last().items[0].toDouble(node.line)) return TValue4.TNum(sortedPts.last().items[1].toDouble(node.line))
                    for (i in 0 until sortedPts.size - 1) {
                        val x0 = sortedPts[i].items[0].toDouble(node.line)
                        val x1 = sortedPts[i+1].items[0].toDouble(node.line)
                        if (x >= x0 && x <= x1) {
                            val y0 = sortedPts[i].items[1].toDouble(node.line)
                            val y1 = sortedPts[i+1].items[1].toDouble(node.line)
                            if (abs(x1 - x0) < 1e-15) throw TesseractError4("interpolate_linear: duplicate x values", node.line)
                            val y = y0 + (y1 - y0) * (x - x0) / (x1 - x0)
                            return TValue4.TNum(y)
                        }
                    }
                    TValue4.TNum(0.0)
                }
                
                "interpolate_lagrange" -> {
                    val points = args[0] as? TValue4.TArray ?: throw TesseractError4("interpolate_lagrange requires array of [x,y] pairs", node.line)
                    if (points.items.isEmpty()) throw TesseractError4("interpolate_lagrange: empty points array", node.line)
                    val x = args[1].toDouble(node.line)
                    val pts = points.items.map { 
                        it as? TValue4.TArray ?: throw TesseractError4("interpolate_lagrange: all points must be arrays", node.line)
                    }
                    for ((i, pt) in pts.withIndex()) {
                        if (pt.items.size < 2) throw TesseractError4("interpolate_lagrange: point $i must have at least 2 elements [x,y]", node.line)
                    }
                    var result = 0.0
                    for (i in pts.indices) {
                        val xi = pts[i].items[0].toDouble(node.line)
                        val yi = pts[i].items[1].toDouble(node.line)
                        var li = 1.0
                        for (j in pts.indices) {
                            if (i != j) {
                                val xj = pts[j].items[0].toDouble(node.line)
                                if (abs(xi - xj) < 1e-15) throw TesseractError4("interpolate_lagrange: duplicate x values", node.line)
                                li *= (x - xj) / (xi - xj)
                            }
                        }
                        result += yi * li
                    }
                    TValue4.TNum(result)
                }
                
                "limit" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("limit requires function", node.line)
                    val a = args[1].toDouble(node.line)
                    val hs = listOf(0.1, 0.01, 0.001, 0.0001, 0.00001, 1e-6, 1e-7)
                    val leftVals = mutableListOf<Double>()
                    val rightVals = mutableListOf<Double>()
                    for (h in hs) {
                        leftVals.add(callTFunction(f, listOf(TValue4.TNum(a - h)), node.line).toDouble(node.line))
                        rightVals.add(callTFunction(f, listOf(TValue4.TNum(a + h)), node.line).toDouble(node.line))
                    }
                    val left = leftVals.last()
                    val right = rightVals.last()
                    if (abs(left - right) < 1e-6) TValue4.TNum((left + right) / 2)
                    else TValue4.TNum(left)
                }
                
                "convolve" -> {
                    val a = args[0] as? TValue4.TArray ?: throw TesseractError4("convolve requires array", node.line)
                    val b = args[1] as? TValue4.TArray ?: throw TesseractError4("convolve requires array", node.line)
                    val n = a.items.size + b.items.size - 1
                    val result = DoubleArray(n)
                    for (i in a.items.indices) {
                        for (j in b.items.indices) {
                            result[i + j] += a.items[i].toDouble(node.line) * b.items[j].toDouble(node.line)
                        }
                    }
                    TValue4.TArray(result.map { TValue4.TNum(it) }.toMutableList())
                }
                
                "moving_average" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("moving_average requires array", node.line)
                    val window = args[1].toLong(node.line).toInt()
                    if (window < 1) throw TesseractError4("window must be >= 1", node.line)
                    val result = mutableListOf<TValue4>()
                    for (i in 0..arr.items.size - window) {
                        var sum = 0.0
                        for (j in 0 until window) sum += arr.items[i + j].toDouble(node.line)
                        result.add(TValue4.TNum(sum / window))
                    }
                    TValue4.TArray(result)
                }
                
                "ohm_v" -> TValue4.TNum(args[0].toDouble(node.line) * args[1].toDouble(node.line))
                "ohm_i" -> { MathGuard4.checkDivision(args[1], node.line); TValue4.TNum(args[0].toDouble(node.line) / args[1].toDouble(node.line)) }
                "ohm_r" -> { MathGuard4.checkDivision(args[1], node.line); TValue4.TNum(args[0].toDouble(node.line) / args[1].toDouble(node.line)) }
                "power" -> TValue4.TNum(args[0].toDouble(node.line) * args[1].toDouble(node.line))
                "voltage_divider" -> {
                    val vin = args[0].toDouble(node.line)
                    val r1 = args[1].toDouble(node.line)
                    val r2 = args[2].toDouble(node.line)
                    TValue4.TNum(vin * r2 / (r1 + r2))
                }
                "impedance_rlc" -> {
                    val r = args[0].toDouble(node.line)
                    val l = args[1].toDouble(node.line)
                    val c = args[2].toDouble(node.line)
                    val f = args[3].toDouble(node.line)
                    val w = 2 * PI * f
                    val xl = w * l
                    val xc = if (c == 0.0) 0.0 else 1 / (w * c)
                    TValue4.TNum(sqrt(r * r + (xl - xc).pow(2)))
                }
                "decibels" -> TValue4.TNum(10 * log10(args[0].toDouble(node.line)))
                "decibels_power" -> TValue4.TNum(10 * log10(args[0].toDouble(node.line)))
                "decibels_voltage" -> TValue4.TNum(20 * log10(args[0].toDouble(node.line)))
                
                "gcd" -> {
                    val a = BigInteger.valueOf(args[0].toLong(node.line))
                    val b = BigInteger.valueOf(args[1].toLong(node.line))
                    TValue4.TBigInt(a.gcd(b))
                }
                
                "lcm" -> {
                    val a = BigInteger.valueOf(args[0].toLong(node.line))
                    val b = BigInteger.valueOf(args[1].toLong(node.line))
                    TValue4.TBigInt(a.divide(a.gcd(b)).multiply(b))
                }
                
                "factorial" -> {
                    var res = BigInteger.ONE
                    for (i in 1..args[0].toLong(node.line).toInt()) res = res.multiply(BigInteger.valueOf(i.toLong()))
                    TValue4.TBigInt(res)
                }
                
                "comb" -> {
                    val n = args[0].toLong(node.line).toInt()
                    val k = args[1].toLong(node.line).toInt()
                    var num = BigInteger.ONE
                    var den = BigInteger.ONE
                    for (i in 0 until k) {
                        num = num.multiply(BigInteger.valueOf((n - i).toLong()))
                        den = den.multiply(BigInteger.valueOf((i + 1).toLong()))
                    }
                    TValue4.TBigInt(num.divide(den))
                }
                
                "perm" -> {
                    val n = args[0].toLong(node.line).toInt()
                    val k = args[1].toLong(node.line).toInt()
                    var res = BigInteger.ONE
                    for (i in 0 until k) res = res.multiply(BigInteger.valueOf((n - i).toLong()))
                    TValue4.TBigInt(res)
                }
                
                "mempty" -> {
                    when (args[0].displayString()) {
                        "num", "int" -> TValue4.TInt(0)
                        "str" -> TValue4.TStr("")
                        "array" -> TValue4.TArray()
                        else -> TValue4.TNull
                    }
                }
                
                "mappend" -> {
                    val l = args[0]
                    val r = args[1]
                    if (l is TValue4.TArray && r is TValue4.TArray) TValue4.TArray((l.items + r.items).toMutableList())
                    else if (l is TValue4.TStr && r is TValue4.TStr) TValue4.TStr(l.value + r.value)
                    else TValue4.TNum(l.toDouble(node.line) + r.toDouble(node.line))
                }
                
                "ap" -> {
                    val fs = args[0] as? TValue4.TArray ?: throw TesseractError4("ap requires array", node.line)
                    val xs = args[1] as? TValue4.TArray ?: throw TesseractError4("ap requires array", node.line)
                    val res = mutableListOf<TValue4>()
                    for (f in fs.items) {
                        for (x in xs.items) {
                            if (f is TValue4.TFunction) res.add(callTFunction(f, listOf(x), node.line))
                        }
                    }
                    TValue4.TArray(res)
                }
                
                "pure" -> TValue4.TArray(mutableListOf(args[0]))
                
                "bind" -> {
                    val m = args[0] as? TValue4.TArray ?: throw TesseractError4("bind requires array", node.line)
                    val f = args[1] as? TValue4.TFunction ?: throw TesseractError4("bind requires function", node.line)
                    val res = mutableListOf<TValue4>()
                    for (x in m.items) {
                        val out = callTFunction(f, listOf(x), node.line)
                        if (out is TValue4.TArray) res.addAll(out.items) else res.add(out)
                    }
                    TValue4.TArray(res)
                }
                
                "fmap" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val m = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("fmap requires array", node.line)
                    val f = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("fmap requires function", node.line)
                    TValue4.TArray(m.items.map { callTFunction(f, listOf(it), node.line) }.toMutableList())
                }

                "open_act" -> {
                    if (args.isEmpty() || args[0] !is TValue4.TStr) throw TesseractError4("open_act requires a string", node.line)
                    throw TesseractOpenActCommand4((args[0] as TValue4.TStr).value)
                }
                "set_seed" -> { standardRandom.setSeed(args[0].toLong(node.line)); TValue4.TInt(1) }
                "random" -> TValue4.TNum(standardRandom.nextDouble())
                "random_int" -> {
                    val min = args[0].toLong(node.line)
                    val max = args[1].toLong(node.line)
                    if (min > max) throw TesseractError4("random_int: min > max", node.line)
                    TValue4.TInt(min + standardRandom.nextInt((max - min + 1).toInt()))
                }
                "secure_random" -> TValue4.TNum(secureRandom.nextDouble())
                "secure_random_int" -> {
                    val min = args[0].toLong(node.line)
                    val max = args[1].toLong(node.line)
                    TValue4.TInt(min + secureRandom.nextInt((max - min + 1).toInt()))
                }
                "char_at" -> {
                    val strVal = args[0]
                    val idx = args[1].toLong(node.line).toInt()
                    if (strVal is TValue4.TStr) {
                        val actualIdx = if (idx < 0) strVal.value.length + idx else idx
                        if (actualIdx < 0 || actualIdx >= strVal.value.length) throw TesseractError4("Index out of bounds", node.line)
                        TValue4.TStr(strVal.value[actualIdx].toString())
                    } else throw TesseractError4("char_at requires string", node.line)
                }
                "len" -> {
                    val arg = args[0]
                    when (arg) {
                        is TValue4.TStr -> TValue4.TInt(arg.value.length.toLong())
                        is TValue4.TArray -> TValue4.TInt(arg.items.size.toLong() + arg.fields.size.toLong())
                        else -> throw TesseractError4("len() requires string or array", node.line)
                    }
                }
                
                // ИСПРАВЛЕНИЕ 3: Добавлена функция append для удобного добавления элементов в массив
                "append" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("append требует массив в первом аргументе", node.line)
                    val item = args[1]
                    val newArr = TValue4.TArray(arr.items.toMutableList())
                    newArr.items.add(item)
                    newArr
                }

                "type_of" -> {
                    val typeStr = when (args[0]) {
                        is TValue4.TNum -> "num"
                        is TValue4.TInt -> "int"
                        is TValue4.TStr -> "str"
                        is TValue4.TBool -> "bool"
                        is TValue4.TArray -> "array"
                        is TValue4.TFunction -> "function"
                        is TValue4.TNull -> "null"
                        is TValue4.TComplex -> "complex"
                        is TValue4.TMatrix -> "matrix"
                        is TValue4.TBigInt -> "bigint"
                        is TValue4.TRational -> "rational"
                        is TValue4.TPoly -> "poly"
                    }
                    TValue4.TStr(typeStr)
                }
                "precise_eq" -> {
                    if (args.size < 2) throw TesseractError4("precise_eq requires two arguments", node.line)
                    TValue4.TBool(abs(args[0].toDouble(node.line) - args[1].toDouble(node.line)) < 1e-12)
                }
                "round_exact" -> {
                    if (args.size < 2) throw TesseractError4("round_exact requires value and decimals", node.line)
                    val value = args[0].toDouble(node.line)
                    val scale = args[1].toLong(node.line).toInt()
                    val bd = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP)
                    TValue4.TNum(bd.toDouble())
                }
                "bd_add" -> {
                    if (args.size < 2) throw TesseractError4("bd_add requires two arguments", node.line)
                    TValue4.TNum((BigDecimal.valueOf(args[0].toDouble(node.line)) + BigDecimal.valueOf(args[1].toDouble(node.line))).toDouble())
                }
                "bd_sub" -> {
                    if (args.size < 2) throw TesseractError4("bd_sub requires two arguments", node.line)
                    TValue4.TNum((BigDecimal.valueOf(args[0].toDouble(node.line)) - BigDecimal.valueOf(args[1].toDouble(node.line))).toDouble())
                }
                "bd_mul" -> {
                    if (args.size < 2) throw TesseractError4("bd_mul requires two arguments", node.line)
                    TValue4.TNum((BigDecimal.valueOf(args[0].toDouble(node.line)) * BigDecimal.valueOf(args[1].toDouble(node.line))).toDouble())
                }
                "bd_div" -> {
                    if (args.size < 2) throw TesseractError4("bd_div requires at least two arguments", node.line)
                    val a = BigDecimal.valueOf(args[0].toDouble(node.line))
                    val b = BigDecimal.valueOf(args[1].toDouble(node.line))
                    val scale = if (args.size >= 3) args[2].toLong(node.line).toInt() else 2
                    TValue4.TNum(a.divide(b, scale, RoundingMode.HALF_UP).toDouble())
                }
                "bd_sum" -> {
                    val list = args.map { it.toDouble(node.line) }
                    if (list.isEmpty()) TValue4.TNum(0.0) else {
                        var sum = BigDecimal.ZERO
                        for (num in list) sum = sum.add(BigDecimal.valueOf(num))
                        TValue4.TNum(sum.toDouble())
                    }
                }
                "sum" -> TValue4.TNum(if (args.isEmpty()) 0.0 else args.sumOf { it.toDouble(node.line) })
                "max_val" -> TValue4.TNum(args.maxOfOrNull { it.toDouble(node.line) } ?: 0.0)
                "min_val" -> TValue4.TNum(args.minOfOrNull { it.toDouble(node.line) } ?: 0.0)
                "count" -> TValue4.TInt(args.size.toLong())
                "toNum" -> TValue4.TNum(args[0].toDouble(node.line))
                "toInt" -> TValue4.TInt(args[0].toLong(node.line))
                "log2" -> { MathGuard4.checkOverflow(ln(args[0].toDouble(node.line)) / ln(2.0), node.line); TValue4.TNum(ln(args[0].toDouble(node.line)) / ln(2.0)) }
                "ln" -> { MathGuard4.checkOverflow(ln(args[0].toDouble(node.line)), node.line); TValue4.TNum(ln(args[0].toDouble(node.line))) }
                "log10" -> { MathGuard4.checkOverflow(log10(args[0].toDouble(node.line)), node.line); TValue4.TNum(log10(args[0].toDouble(node.line))) }
                "sqrt" -> { MathGuard4.checkOverflow(sqrt(args[0].toDouble(node.line)), node.line); TValue4.TNum(sqrt(args[0].toDouble(node.line))) }
                "cbrt" -> TValue4.TNum(cbrt(args[0].toDouble(node.line)))
                "root" -> { MathGuard4.checkOverflow(args[0].toDouble(node.line).pow(1.0 / args[1].toDouble(node.line)), node.line); TValue4.TNum(args[0].toDouble(node.line).pow(1.0 / args[1].toDouble(node.line))) }
                "pow" -> { val r = args[0].toDouble(node.line).pow(args[1].toDouble(node.line)); MathGuard4.checkOverflow(r, node.line); TValue4.TNum(r) }
                "exp" -> { val r = exp(args[0].toDouble(node.line)); MathGuard4.checkOverflow(r, node.line); TValue4.TNum(r) }
                "sin" -> TValue4.TNum(sin(args[0].toDouble(node.line)))
                "cos" -> TValue4.TNum(cos(args[0].toDouble(node.line)))
                "tan" -> { val rad = args[0].toDouble(node.line); if (abs(cos(rad)) < 1e-10) throw TesseractError4("tan infinity", node.line, callStack.toList()); TValue4.TNum(tan(rad)) }
                "asin" -> { if (args[0].toDouble(node.line) !in -1.0..1.0) throw TesseractError4("asin domain", node.line, callStack.toList()); TValue4.TNum(asin(args[0].toDouble(node.line))) }
                "acos" -> { if (args[0].toDouble(node.line) !in -1.0..1.0) throw TesseractError4("acos domain", node.line, callStack.toList()); TValue4.TNum(acos(args[0].toDouble(node.line))) }
                "atan" -> TValue4.TNum(atan(args[0].toDouble(node.line)))
                "sinh" -> TValue4.TNum(sinh(args[0].toDouble(node.line)))
                "cosh" -> TValue4.TNum(cosh(args[0].toDouble(node.line)))
                "tanh" -> TValue4.TNum(tanh(args[0].toDouble(node.line)))
                "floor" -> TValue4.TInt(floor(args[0].toDouble(node.line)).toLong())
                "ceil" -> TValue4.TInt(ceil(args[0].toDouble(node.line)).toLong())
                "round" -> TValue4.TInt(round(args[0].toDouble(node.line)).toLong())
                "min" -> if (args[0].toDouble(node.line) < args[1].toDouble(node.line)) args[0] else args[1]
                "max" -> if (args[0].toDouble(node.line) > args[1].toDouble(node.line)) args[0] else args[1]
                
                "rev" -> {
                    val arg = args[0]
                    when (arg) {
                        is TValue4.TInt -> TValue4.TInt(arg.value.toString().reversed().toLongOrNull() ?: 0L)
                        is TValue4.TStr -> TValue4.TStr(arg.value.reversed())
                        else -> throw TesseractError4("rev requires string or int", node.line)
                    }
                }
                
                "setmetatable" -> {
                    if (args.size != 2) throw TesseractError4("setmetatable requires two arguments", node.line)
                    val table = args[0]
                    val mt = args[1]
                    if (table is TValue4.TArray) {
                        table.metatable = mt
                        TValue4.TNull
                    } else {
                        throw TesseractError4("setmetatable first argument must be an array/table", node.line)
                    }
                }
                
                "map" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("map requires array", node.line)
                    val f = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("map requires function", node.line)
                    TValue4.TArray(arr.items.map { callTFunction(f, listOf(it), node.line) }.toMutableList())
                }
                "filter" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("filter requires array", node.line)
                    val f = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("filter requires function", node.line)
                    TValue4.TArray(arr.items.filter { callTFunction(f, listOf(it), node.line).toBoolean() }.toMutableList())
                }
                "reduce" -> {
                    if (args.size < 2) throw TesseractError4("reduce requires at least 2 arguments", node.line)
                    val arr = args.firstOrNull { it is TValue4.TArray } as? TValue4.TArray 
                        ?: throw TesseractError4("reduce requires array", node.line)
                    val f = args.firstOrNull { it is TValue4.TFunction } as? TValue4.TFunction 
                        ?: throw TesseractError4("reduce requires function", node.line)
                    
                    var currentAcc: TValue4
                    val startIndex: Int
                    
                    if (args.size == 2) {
                        if (arr.items.isEmpty()) throw TesseractError4("reduce of empty array with no initial value", node.line)
                        currentAcc = arr.items[0]
                        startIndex = 1
                    } else {
                        val nonArrNonFunc = args.firstOrNull { it !is TValue4.TArray && it !is TValue4.TFunction }
                        if (nonArrNonFunc == null) 
                            throw TesseractError4("reduce: third argument must be initial value (not array or function)", node.line)
                        currentAcc = nonArrNonFunc
                        startIndex = 0
                    }
                    
                    for (i in startIndex until arr.items.size) {
                        currentAcc = callTFunction(f, listOf(currentAcc, arr.items[i]), node.line)
                    }
                    currentAcc
                }
                "remove_at" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("remove_at requires array", node.line)
                    val idx = if (a0 is TValue4.TInt) a0 else a1 as? TValue4.TInt ?: throw TesseractError4("remove_at requires int", node.line)
                    val rawIndex = idx.value.toInt()
                    val actualIndex = if (rawIndex < 0) arr.items.size + rawIndex else rawIndex
                    if (actualIndex < 0 || actualIndex >= arr.items.size) throw TesseractError4("remove_at: index out of bounds", node.line)
                    val newArr = arr.items.toMutableList()
                    newArr.removeAt(actualIndex)
                    TValue4.TArray(newArr)
                }
                "apply" -> {
                    val func = args[0] as? TValue4.TFunction ?: throw TesseractError4("apply requires function", node.line)
                    val argsArr = args[1] as? TValue4.TArray ?: throw TesseractError4("apply requires array", node.line)
                    callTFunction(func, argsArr.items, node.line)
                }
                "compose" -> {
                    val f = args[0] as? TValue4.TFunction ?: throw TesseractError4("compose requires function", node.line)
                    val g = args[1] as? TValue4.TFunction ?: throw TesseractError4("compose requires function", node.line)
                    val composeEnv = env.createChild()
                    composeEnv.set("__compose_f__", f)
                    composeEnv.set("__compose_g__", g)
                    TValue4.TFunction(
                        listOf("x"),
                        listOf(Stmt4.ReturnStmt(Expr4.FuncCall("__compose_f__", listOf(Expr4.FuncCall("__compose_g__", listOf(Expr4.VarRef("x", node.line)), node.line)), node.line), node.line)),
                        composeEnv
                    )
                }
                "memoize" -> {
                    val func = args[0] as? TValue4.TFunction ?: throw TesseractError4("memoize requires function", node.line)
                    val wrapper = TValue4.TArray(mutableListOf(func))
                    wrapper.fields["__memoized__"] = TValue4.TBool(true)
                    wrapper.fields["__note__"] = TValue4.TStr("Memoization marker - caching not implemented in this version")
                    wrapper
                }
                "flatten" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("flatten requires array", node.line)
                    val result = mutableListOf<TValue4>()
                    fun flattenRec(item: TValue4) {
                        if (item is TValue4.TArray) {
                            for (sub in item.items) flattenRec(sub)
                        } else {
                            result.add(item)
                        }
                    }
                    flattenRec(arr)
                    TValue4.TArray(result)
                }
                "zip" -> {
                    val a = args[0] as? TValue4.TArray ?: throw TesseractError4("zip requires array", node.line)
                    val b = args[1] as? TValue4.TArray ?: throw TesseractError4("zip requires array", node.line)
                    val result = mutableListOf<TValue4>()
                    val minLen = minOf(a.items.size, b.items.size)
                    for (i in 0 until minLen) {
                        result.add(TValue4.TArray(mutableListOf(a.items[i], b.items[i])))
                    }
                    TValue4.TArray(result)
                }
                "range" -> {
                    val start = if (args.size >= 2) args[0].toLong(node.line) else 0L
                    val end = if (args.size >= 2) args[1].toLong(node.line) else args[0].toLong(node.line)
                    val step = if (args.size == 3) args[2].toLong(node.line) else (if (start <= end) 1L else -1L)
                    if (step == 0L) throw TesseractError4("range step cannot be zero", node.line)
                    val result = mutableListOf<TValue4>()
                    var i = start
                    if (step > 0) {
                        while (i < end) {
                            result.add(TValue4.TInt(i))
                            i += step
                        }
                    } else {
                        while (i > end) {
                            result.add(TValue4.TInt(i))
                            i += step
                        }
                    }
                    TValue4.TArray(result)
                }
                
                "all" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("all requires array", node.line)
                    val pred = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("all requires function", node.line)
                    var res = true
                    for (item in arr.items) {
                        if (!callTFunction(pred, listOf(item), node.line).toBoolean()) { res = false; break }
                    }
                    TValue4.TBool(res)
                }
                "any" -> {
                    val a0 = args[0]; val a1 = args[1]
                    val arr = if (a0 is TValue4.TArray) a0 else a1 as? TValue4.TArray ?: throw TesseractError4("any requires array", node.line)
                    val pred = if (a0 is TValue4.TFunction) a0 else a1 as? TValue4.TFunction ?: throw TesseractError4("any requires function", node.line)
                    var res = false
                    for (item in arr.items) {
                        if (callTFunction(pred, listOf(item), node.line).toBoolean()) { res = true; break }
                    }
                    TValue4.TBool(res)
                }
                
                "permutations" -> {
                    val arr = (args[0] as? TValue4.TArray ?: throw TesseractError4("permutations requires array", node.line)).items
                    if (arr.size > 10) throw TesseractError4("permutations: array too large (max 10 elements)", node.line)
                    if (arr.size <= 1) {
                        TValue4.TArray(mutableListOf(TValue4.TArray(arr.toMutableList())))
                    } else {
                        val result = mutableListOf<TValue4>()
                        for (i in arr.indices) {
                            val current = arr[i]
                            val rest = arr.filterIndexed { idx, _ -> idx != i }
                            for (subPerm in generatePermutations4(rest)) {
                                if (subPerm is TValue4.TArray) {
                                    val newPerm = mutableListOf(current)
                                    newPerm.addAll(subPerm.items)
                                    result.add(TValue4.TArray(newPerm))
                                }
                            }
                        }
                        TValue4.TArray(result)
                    }
                }
                
                "combinations" -> {
                    val arr = args[0] as? TValue4.TArray ?: throw TesseractError4("combinations requires array", node.line)
                    val k = args[1] as? TValue4.TInt ?: throw TesseractError4("combinations requires int", node.line)
                    if (arr.items.size > 20) throw TesseractError4("combinations: array too large (max 20 elements)", node.line)
                    if (k.value.toInt() == 0) {
                        TValue4.TArray(mutableListOf(TValue4.TArray(mutableListOf())))
                    } else if (arr.items.isEmpty()) {
                        TValue4.TArray(mutableListOf())
                    } else {
                        val result = mutableListOf<TValue4>()
                        val first = arr.items[0]
                        val rest = arr.items.drop(1)
                        for (sub in generateCombinations4(rest, k.value.toInt() - 1)) {
                            if (sub is TValue4.TArray) {
                                val newComb = mutableListOf(first)
                                newComb.addAll(sub.items)
                                result.add(TValue4.TArray(newComb))
                            }
                        }
                        result.addAll(generateCombinations4(rest, k.value.toInt()))
                        TValue4.TArray(result)
                    }
                }
                
                "match" -> {
                    val pattern = args[0]
                    val value = args[1]
                    fun matchRec(p: TValue4, v: TValue4): Map<String, TValue4>? {
                        if (p is TValue4.TStr && p.value.startsWith("?")) return mapOf(p.value to v)
                        if (p is TValue4.TStr && p.value.startsWith("_")) return emptyMap()
                        if (p is TValue4.TNum && v is TValue4.TNum) return if (abs(p.value - v.value) < 1e-9) emptyMap() else null
                        if (p is TValue4.TInt && v is TValue4.TInt) return if (p.value == v.value) emptyMap() else null
                        if (p is TValue4.TStr && v is TValue4.TStr) return if (p.value == v.value) emptyMap() else null
                        if (p is TValue4.TBool && v is TValue4.TBool) return if (p.value == v.value) emptyMap() else null
                        if (p is TValue4.TArray && v is TValue4.TArray) {
                            if (p.items.size != v.items.size) return null
                            var bindings = mutableMapOf<String, TValue4>()
                            for (i in p.items.indices) {
                                val sub = matchRec(p.items[i], v.items[i]) ?: return null
                                for ((key, val_) in sub) {
                                    if (bindings.containsKey(key) && bindings[key] != val_) return null
                                    bindings[key] = val_
                                }
                            }
                            return bindings
                        }
                        return null
                    }
                    val bindings = matchRec(pattern, value)
                    if (bindings != null) {
                        val result = TValue4.TArray(mutableListOf())
                        for ((key, val_) in bindings) result.fields[key] = val_
                        result.fields["__matched__"] = TValue4.TBool(true)
                        result
                    } else {
                        val result = TValue4.TArray(mutableListOf())
                        result.fields["__matched__"] = TValue4.TBool(false)
                        result
                    }
                }
                
                "product" -> {
                    val arrays = (args[0] as? TValue4.TArray ?: throw TesseractError4("product requires array", node.line)).items
                    if (arrays.isEmpty()) {
                        TValue4.TArray(mutableListOf())
                    } else {
                        fun cartesian(lists: List<List<TValue4>>): List<List<TValue4>> {
                            if (lists.isEmpty()) return listOf(emptyList())
                            val first = lists[0]
                            val restResult = cartesian(lists.drop(1))
                            val result = mutableListOf<List<TValue4>>()
                            for (item in first) {
                                for (rest in restResult) {
                                    result.add(listOf(item) + rest)
                                }
                            }
                            return result
                        }
                        val listsOfItems = arrays.map { if (it is TValue4.TArray) it.items else listOf(it) }
                        val result = cartesian(listsOfItems).map { TValue4.TArray(it.toMutableList()) }
                        TValue4.TArray(result.toMutableList())
                    }
                }
                
                // ТОЧЕЧНЫЙ ПАТЧ: Добавлено .reversed() для корректного порядка коэффициентов (от младшей степени к старшей)
                "poly" -> TValue4.TPoly(args.map { it.toDouble(node.line) }.reversed())
                
                "eval_poly" -> {
                    val p = args[0] as? TValue4.TPoly ?: throw TesseractError4("eval_poly requires poly", node.line)
                    val x = args[1].toDouble(node.line)
                    var res = 0.0
                    for (i in p.coeffs.indices.reversed()) {
                        res = res * x + p.coeffs[i]
                    }
                    TValue4.TNum(res)
                }
                
                "exit" -> throw TesseractExitCommand4(if (args.isNotEmpty()) args[0].toLong(node.line) else 0L)
                else -> throw TesseractError4("Unknown function: ${node.name}", node.line, callStack.toList())
            }
        } finally {
            callStack.removeLast()
        }
        return result
    }

    private fun generatePermutations4(arr: List<TValue4>): List<TValue4> {
        if (arr.size <= 1) return listOf(TValue4.TArray(arr.toMutableList()))
        val result = mutableListOf<TValue4>()
        for (i in arr.indices) {
            val current = arr[i]; val rest = arr.filterIndexed { idx, _ -> idx != i }
            for (subPerm in generatePermutations4(rest)) {
                if (subPerm is TValue4.TArray) { val newPerm = mutableListOf(current); newPerm.addAll(subPerm.items); result.add(TValue4.TArray(newPerm)) }
            }
        }
        return result
    }

    private fun generateCombinations4(arr: List<TValue4>, k: Int): List<TValue4> {
        if (k == 0) return listOf(TValue4.TArray(mutableListOf()))
        if (arr.isEmpty()) return emptyList()
        val result = mutableListOf<TValue4>(); val first = arr[0]; val rest = arr.drop(1)
        for (sub in generateCombinations4(rest, k - 1)) {
            if (sub is TValue4.TArray) { val newComb = mutableListOf(first); newComb.addAll(sub.items); result.add(TValue4.TArray(newComb)) }
        }
        result.addAll(generateCombinations4(rest, k))
        return result
    }

    private fun callTFunction(func: TValue4.TFunction, args: List<TValue4>, line: Int): TValue4 {
        totalUserFunctionCalls++; if (totalUserFunctionCalls > 1_000_000) throw TesseractError4("Call limit", line)
        if (++recursionDepth > 2000) throw TesseractError4("Recursion depth", line)
        callStack.add("<closure>")
        val localEnv = func.closureEnv.createChild(); val oldEnv = env; env = localEnv
        for (i in func.params.indices) env.declare(func.params[i], args[i])
        val result = try { var res: TValue4? = null; for (stmt in func.body) res = evalStmt(stmt); res ?: TValue4.TInt(0) } catch (e: ReturnValue4) { e.value ?: TValue4.TInt(0) } finally { env = oldEnv; callStack.removeLast(); recursionDepth-- }
        return result
    }
}

object TesseractEngine4 {
    fun evaluate(context: Context, script: String, constantOverrides: Map<String, String> = emptyMap()): String {
        return try { 
            val lexer = Lexer4(script); val tokens = lexer.tokenize(); val parser = Parser4(tokens); val ast = parser.parse(); val evaluator = Evaluator4(context); evaluator.evaluate(ast, constantOverrides) 
        } 
        catch (e: TesseractError4) { val sb = StringBuilder(); sb.appendLine("💥 PERCEPTRON ERROR (Line ${e.line}): ${e.message}"); if (e.callStack.isNotEmpty()) { sb.appendLine("Call Stack:"); e.callStack.forEachIndexed { index, trace -> sb.appendLine("   ${" ".repeat(index)}-> $trace") } }; sb.toString().trim() } 
        catch (e: StackOverflowError) { "💥 PERCEPTRON ERROR: Stack overflow." } 
        catch (e: TesseractOpenActCommand4) { throw e } 
        catch (e: Exception) { "💥 CRITICAL ERROR: ${e.message ?: "Unknown"}" }
    }
}
