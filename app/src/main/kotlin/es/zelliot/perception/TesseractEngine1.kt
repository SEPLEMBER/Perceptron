package es.zelliot.perceptron

import android.content.Context
import kotlin.math.*
import java.security.SecureRandom
import java.util.Random

// ============================================================================
// 1. EXCEPTIONS AND DATA TYPES
// ============================================================================

/**
 * Custom exception for interpreter errors.
 * @param messageResId Resource ID for the localized error message.
 * @param line The line number where the error occurred.
 * @param callStack The current function call stack for debugging.
 * @param formatArgs Arguments to format the localized string.
 */
class TesseractError(
    val messageResId: Int, 
    val line: Int, 
    val callStack: List<String> = emptyList(), 
    vararg val formatArgs: Any
) : Exception()

/** Exception used to immediately return a value from a function. */
class ReturnValue(val value: TValue?) : Exception()

/** Exception used to trigger application exit with an optional delay. */
class TesseractExitCommand(val delayMs: Long) : Exception()

/** Exception used to request opening an external Android Activity. */
class TesseractOpenActCommand(val packageName: String) : Exception()

/**
 * Sealed class representing all possible value types in the Perceptron language.
 */
sealed class TValue {
    data class TNum(val value: Double) : TValue()
    data class TInt(val value: Long) : TValue()
    data class TStr(val value: String) : TValue()
    
    /** Safely converts the value to Double, throwing an error if incompatible. */
    fun toDouble(): Double = when (this) { 
        is TNum -> value
        is TInt -> value.toDouble()
        else -> throw TesseractError(R.string.err_expected_number, 0) 
    }
    
    /** Safely converts the value to Long, throwing an error if incompatible. */
    fun toLong(): Long = when (this) { 
        is TNum -> value.toLong()
        is TInt -> value
        else -> throw TesseractError(R.string.err_expected_int, 0) 
    }

    /** Returns a user-friendly string representation of the value. */
    fun displayString(): String = when (this) {
        is TNum -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is TInt -> value.toString()
        is TStr -> value
    }
}

/**
 * Utility object to prevent mathematical crashes and ensure safe operations.
 */
object MathGuard {
    fun checkDivision(b: TValue, line: Int) {
        if (b.toDouble() == 0.0) throw TesseractError(R.string.err_div_by_zero, line)
    }
    
    fun checkLogarithm(x: TValue, line: Int) {
        if (x.toDouble() <= 0.0) throw TesseractError(R.string.err_log_non_positive, line)
    }
    
    fun checkRoot(x: TValue, degree: TValue, line: Int) {
        if (x.toDouble() < 0.0 && degree.toDouble() % 2 == 0.0) throw TesseractError(R.string.err_even_root_negative, line)
    }
    
    fun checkOverflow(result: Double, line: Int) {
        if (result.isInfinite()) throw TesseractError(R.string.err_overflow, line)
        if (result.isNaN()) throw TesseractError(R.string.err_nan, line)
    }
}

// ============================================================================
// 2. LEXER (Tokenization)
// ============================================================================

/**
 * Enum representing all recognized lexical tokens in the Perceptron language.
 */
enum class TokenType {
    NUMBER, STRING, IDENTIFIER, PLUS, MINUS, MUL, DIV, INT_DIV, MOD, POW, XOR,
    LPAREN, RPAREN, LBRACE, RBRACE, COMMA, PIPE, ASSIGN, COLON, ARROW,
    GT, LT, GTE, LTE, EQ, NEQ, AND, OR,
    FN, VAL, CONST, RETURN, ASSERT, IF, THEN, ELSE, WHILE, DO, SEPARATOR, EXIT, EOF
}

data class Token(val type: TokenType, val value: String, val line: Int)

/**
 * Lexical analyzer that converts raw source code string into a list of Tokens.
 */
class Lexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private val tokens = mutableListOf<Token>()

    private fun currentChar(): Char = if (pos < source.length) source[pos] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (pos + offset < source.length) source[pos + offset] else '\u0000'

    private fun advance(): Char {
        val c = currentChar()
        pos++
        if (c == '\n') line++
        return c
    }

    private fun addToken(type: TokenType, value: String) {
        tokens.add(Token(type, value, line))
    }

    fun tokenize(): List<Token> {
        while (pos < source.length) {
            val c = currentChar()
            when {
                c.isWhitespace() -> advance()
                c == '\uFEFF' -> advance() // Skip UTF-8 BOM
                c == ';' -> advance() // Ignore statement terminators
                c == '#' -> { // Single-line comment
                    while (pos < source.length && currentChar() != '\n') advance()
                }
                c.isDigit() || (c == '.' && peek().isDigit()) -> readNumber()
                c == '"' -> readString()
                c.isLetter() || c == '_' -> readIdentifier()
                
                c == '+' -> { addToken(TokenType.PLUS, "+"); advance() }
                c == '-' -> {
                    when {
                        peek() == '>' -> { addToken(TokenType.ARROW, "->"); advance(); advance() }
                        peek() == '-' && peek(2) == '-' -> { addToken(TokenType.SEPARATOR, "---"); advance(); advance(); advance() }
                        else -> { addToken(TokenType.MINUS, "-"); advance() }
                    }
                }
                c == '*' -> { addToken(TokenType.MUL, "*"); advance() }
                c == '/' -> {
                    if (peek() == '/') { // Integer division operator
                        addToken(TokenType.INT_DIV, "//"); advance(); advance()
                    } else {
                        addToken(TokenType.DIV, "/"); advance()
                    }
                }
                c == '%' -> { addToken(TokenType.MOD, "%"); advance() }
                c == '^' -> {
                    if (peek() == '^') { addToken(TokenType.XOR, "^^"); advance(); advance() }
                    else { addToken(TokenType.POW, "^"); advance() }
                }
                c == '>' -> {
                    if (peek() == '=') { addToken(TokenType.GTE, ">="); advance(); advance() }
                    else { addToken(TokenType.GT, ">"); advance() }
                }
                c == '<' -> {
                    if (peek() == '=') { addToken(TokenType.LTE, "<="); advance(); advance() }
                    else { addToken(TokenType.LT, "<"); advance() }
                }
                c == '=' -> {
                    if (peek() == '=') { addToken(TokenType.EQ, "=="); advance(); advance() }
                    else { addToken(TokenType.ASSIGN, "="); advance() }
                }
                c == '!' -> {
                    if (peek() == '=') { addToken(TokenType.NEQ, "!="); advance(); advance() }
                    else throw TesseractError(R.string.err_unknown_char_unicode, line, emptyList(), "!", '!'.code)
                }
                c == '&' -> {
                    if (peek() == '&') { addToken(TokenType.AND, "&&"); advance(); advance() }
                    else throw TesseractError(R.string.err_unknown_char_unicode, line, emptyList(), "&", '&'.code)
                }
                c == '|' -> {
                    when {
                        peek() == '|' -> { addToken(TokenType.OR, "||"); advance(); advance() }
                        peek() == '>' -> { addToken(TokenType.PIPE, "|>"); advance(); advance() }
                        else -> throw TesseractError(R.string.err_unknown_char_unicode, line, emptyList(), "|", '|'.code)
                    }
                }
                c == '(' -> { addToken(TokenType.LPAREN, "("); advance() }
                c == ')' -> { addToken(TokenType.RPAREN, ")"); advance() }
                c == '{' -> { addToken(TokenType.LBRACE, "{"); advance() }
                c == '}' -> { addToken(TokenType.RBRACE, "}"); advance() }
                c == ',' -> { addToken(TokenType.COMMA, ","); advance() }
                c == ':' -> { addToken(TokenType.COLON, ":"); advance() }
                
                else -> throw TesseractError(R.string.err_unknown_char_unicode, line, emptyList(), c, c.code)
            }
        }
        tokens.add(Token(TokenType.EOF, "", line))
        return tokens
    }

    private fun readNumber() {
        val start = pos
        while (pos < source.length && (currentChar().isDigit() || currentChar() == '.')) {
            advance()
        }
        // Handle scientific notation (e.g., 1e-5)
        if (currentChar() == 'e' || currentChar() == 'E') {
            advance()
            if (currentChar() == '+' || currentChar() == '-') {
                advance()
            }
            while (pos < source.length && currentChar().isDigit()) {
                advance()
            }
        }
        addToken(TokenType.NUMBER, source.substring(start, pos))
    }

    private fun readString() {
        advance() // Skip opening quote
        val start = pos
        while (pos < source.length && currentChar() != '"') {
            if (currentChar() == '\\' && peek() == '"') advance() // Handle escaped quotes
            advance()
        }
        addToken(TokenType.STRING, source.substring(start, pos))
        if (pos < source.length && currentChar() == '"') advance() // Skip closing quote
    }

    private fun readIdentifier() {
        val start = pos
        while (pos < source.length && (currentChar().isLetterOrDigit() || currentChar() == '_')) {
            advance()
        }
        var word = source.substring(start, pos)
        
        // SECURITY/USABILITY: Normalize common Cyrillic lookalikes to Latin to prevent hidden bugs
        word = word.replace('а', 'a').replace('А', 'A')
                   .replace('в', 'v').replace('В', 'V')
                   .replace('е', 'e').replace('Е', 'E')
                   .replace('о', 'o').replace('О', 'O')
                   .replace('р', 'r').replace('Р', 'R')
                   .replace('с', 'c').replace('С', 'C')
                   .replace('у', 'y').replace('У', 'Y')
                   .replace('х', 'x').replace('Х', 'X')

        // Catch common typo: "rate" typed as "ate" due to missing first letter
        if (word == "ate") {
            throw TesseractError(R.string.err_ate_typo, line)
        }

        val type = when (word.lowercase()) {
            "fn" -> TokenType.FN
            "val", "var" -> TokenType.VAL
            "const" -> TokenType.CONST
            "return" -> TokenType.RETURN
            "assert" -> TokenType.ASSERT
            "if" -> TokenType.IF
            "then" -> TokenType.THEN
            "else" -> TokenType.ELSE
            "while" -> TokenType.WHILE
            "do" -> TokenType.DO
            "exit" -> TokenType.EXIT
            "and" -> TokenType.AND
            "or" -> TokenType.OR
            else -> TokenType.IDENTIFIER
        }
        addToken(type, word)
    }
}

// ============================================================================
// 3. AST (Abstract Syntax Tree)
// ============================================================================

/** Base class for all AST nodes. */
sealed class Node { abstract val line: Int }

/** Expressions evaluate to a value. */
sealed class Expr : Node() {
    data class NumLit(val value: Double, override val line: Int) : Expr()
    data class IntLit(val value: Long, override val line: Int) : Expr()
    data class StrLit(val value: String, override val line: Int) : Expr()
    data class VarRef(val name: String, override val line: Int) : Expr()
    data class BinaryOp(val left: Expr, val op: TokenType, val right: Expr, override val line: Int) : Expr()
    data class UnaryOp(val op: TokenType, val operand: Expr, override val line: Int) : Expr()
    data class FuncCall(val name: String, val args: List<Expr>, override val line: Int) : Expr()
    data class Pipeline(val left: Expr, val right: Expr, override val line: Int) : Expr()
    data class IfElse(val cond: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int) : Expr()
}

/** Statements perform actions but do not necessarily return a value. */
sealed class Stmt : Node() {
    data class Assignment(val name: String, val value: Expr, override val line: Int) : Stmt()
    data class FunctionDef(val name: String, val params: List<String>, val body: List<Stmt>, override val line: Int) : Stmt()
    data class AssertStmt(val condition: Expr, override val line: Int) : Stmt()
    data class ReturnStmt(val value: Expr?, override val line: Int) : Stmt()
    data class WhileStmt(val cond: Expr, val body: List<Stmt>, override val line: Int) : Stmt()
    data class ExprStmt(val expr: Expr, override val line: Int) : Stmt()
    data class ExitStmt(val delayMs: Long, override val line: Int) : Stmt()
    object SeparatorStmt : Stmt() { override val line: Int = 0 }
}

// ============================================================================
// 4. PARSER (Recursive Descent)
// ============================================================================

/**
 * Recursive descent parser that converts a list of Tokens into an Abstract Syntax Tree (AST).
 * Enforces operator precedence and syntax rules.
 */
class Parser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(offset: Int = 0): Token = tokens.getOrNull(pos + offset) ?: Token(TokenType.EOF, "", 0)
    private fun advance(): Token = tokens[pos++]
    
    private fun expect(type: TokenType): Token {
        val current = peek()
        if (current.type != type) throw TesseractError(R.string.err_expected_token, current.line, emptyList(), type, current.value)
        return advance()
    }

    fun parse(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (peek().type != TokenType.EOF) {
            if (peek().type == TokenType.SEPARATOR) {
                advance()
                statements.add(Stmt.SeparatorStmt)
            } else {
                statements.add(parseStatement())
            }
        }
        return statements
    }

    private fun parseStatement(): Stmt {
        val current = peek()
        return when (current.type) {
            TokenType.FN -> parseFunctionDef()
            TokenType.ASSERT -> { advance(); Stmt.AssertStmt(parseExpression(), peek().line) }
            TokenType.RETURN -> {
                advance()
                val hasValue = peek().type != TokenType.EOF && peek().type != TokenType.RBRACE && peek().type != TokenType.SEPARATOR
                Stmt.ReturnStmt(if (hasValue) parseExpression() else null, current.line)
            }
            TokenType.WHILE -> parseWhile()
            TokenType.EXIT -> {
                advance()
                val delay = if (peek().type == TokenType.NUMBER) {
                    advance().value.toLong()
                } else {
                    0L
                }
                Stmt.ExitStmt(delay, current.line)
            }
            TokenType.VAL, TokenType.CONST -> {
                advance()
                val nameToken = expect(TokenType.IDENTIFIER)
                // Allow optional type hints (e.g., val x: Int = 5), though currently ignored in dynamic typing
                if (peek().type == TokenType.COLON) { advance(); advance() }
                expect(TokenType.ASSIGN)
                Stmt.Assignment(nameToken.value, parseExpression(), nameToken.line)
            }
            TokenType.IDENTIFIER -> {
                if (peek(1).type == TokenType.ASSIGN) {
                    val nameToken = advance()
                    advance()
                    Stmt.Assignment(nameToken.value, parseExpression(), nameToken.line)
                } else {
                    Stmt.ExprStmt(parseExpression(), current.line)
                }
            }
            else -> Stmt.ExprStmt(parseExpression(), current.line)
        }
    }

    private fun parseWhile(): Stmt {
        advance()
        val cond = parseExpression()
        expect(TokenType.DO)
        return Stmt.WhileStmt(cond, parseBlock(), cond.line)
    }

    private fun parseFunctionDef(): Stmt {
        advance()
        val nameToken = expect(TokenType.IDENTIFIER)
        expect(TokenType.LPAREN)
        val params = mutableListOf<String>()
        
        if (peek().type != TokenType.RPAREN) {
            do {
                params.add(expect(TokenType.IDENTIFIER).value)
                // Allow optional type hints for parameters
                if (peek().type == TokenType.COLON) { advance(); advance() }
                if (peek().type == TokenType.COMMA) advance() else break
            } while (peek().type != TokenType.RPAREN)
        }
        expect(TokenType.RPAREN)
        // Allow optional return type arrow (e.g., fn add(a, b) -> { ... })
        if (peek().type == TokenType.ARROW) { advance(); advance() }
        return Stmt.FunctionDef(nameToken.value, params, parseBlock(), nameToken.line)
    }

    private fun parseBlock(): List<Stmt> {
        expect(TokenType.LBRACE)
        val stmts = mutableListOf<Stmt>()
        while (peek().type != TokenType.RBRACE && peek().type != TokenType.EOF) {
            if (peek().type == TokenType.SEPARATOR) advance()
            else stmts.add(parseStatement())
        }
        expect(TokenType.RBRACE)
        return stmts
    }

    // --- Expression Parsing (Ordered by Precedence, lowest to highest) ---
    
    private fun parseExpression(): Expr = parseLogicalOr()
    
    private fun parseLogicalOr(): Expr {
        var left = parseLogicalAnd()
        while (peek().type == TokenType.OR) {
            val op = advance().type
            left = Expr.BinaryOp(left, op, parseLogicalAnd(), left.line)
        }
        return left
    }
    
    private fun parseLogicalAnd(): Expr {
        var left = parsePipeline()
        while (peek().type == TokenType.AND) {
            val op = advance().type
            left = Expr.BinaryOp(left, op, parsePipeline(), left.line)
        }
        return left
    }

    private fun parsePipeline(): Expr {
        var left = parseComparison()
        while (peek().type == TokenType.PIPE) { 
            advance()
            left = Expr.Pipeline(left, parseComparison(), left.line) 
        }
        return left
    }
    
    private fun parseComparison(): Expr {
        var left = parseXor()
        while (peek().type in listOf(TokenType.GT, TokenType.LT, TokenType.GTE, TokenType.LTE, TokenType.EQ, TokenType.NEQ)) {
            val op = advance().type
            left = Expr.BinaryOp(left, op, parseXor(), left.line)
        }
        return left
    }
    
    private fun parseXor(): Expr {
        var left = parseAddition()
        while (peek().type == TokenType.XOR) {
            left = Expr.BinaryOp(left, advance().type, parseAddition(), left.line)
        }
        return left
    }
    
    private fun parseAddition(): Expr {
        var left = parseMultiplication()
        while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
            left = Expr.BinaryOp(left, advance().type, parseMultiplication(), left.line)
        }
        return left
    }
    
    private fun parseMultiplication(): Expr {
        var left = parseExponentiation()
        while (peek().type in listOf(TokenType.MUL, TokenType.DIV, TokenType.INT_DIV, TokenType.MOD)) {
            left = Expr.BinaryOp(left, advance().type, parseExponentiation(), left.line)
        }
        return left
    }
    
    private fun parseExponentiation(): Expr {
        val base = parseUnary()
        return if (peek().type == TokenType.POW) { 
            advance()
            // Right-associative for exponentiation
            Expr.BinaryOp(base, TokenType.POW, parseExponentiation(), base.line) 
        } else base
    }
    
    private fun parseUnary(): Expr {
        return if (peek().type == TokenType.MINUS || peek().type == TokenType.PLUS) {
            Expr.UnaryOp(advance().type, parseUnary(), peek().line)
        } else parsePrimary()
    }
    
    private fun parsePrimary(): Expr {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER -> { 
                advance()
                if (token.value.contains('.') || token.value.contains('e') || token.value.contains('E')) {
                    Expr.NumLit(token.value.toDouble(), token.line) 
                } else {
                    Expr.IntLit(token.value.toLong(), token.line) 
                }
            }
            TokenType.STRING -> { advance(); Expr.StrLit(token.value, token.line) }
            TokenType.IF -> {
                advance()
                val cond = parseExpression()
                expect(TokenType.THEN)
                val thenExpr = parseExpression()
                expect(TokenType.ELSE)
                Expr.IfElse(cond, thenExpr, parseExpression(), token.line)
            }
            TokenType.IDENTIFIER -> {
                advance()
                if (peek().type == TokenType.LPAREN) {
                    advance()
                    val args = mutableListOf<Expr>()
                    if (peek().type != TokenType.RPAREN) {
                        args.add(parseExpression())
                        while (peek().type == TokenType.COMMA) { 
                            advance()
                            args.add(parseExpression()) 
                        }
                    }
                    expect(TokenType.RPAREN)
                    Expr.FuncCall(token.value, args, token.line)
                } else Expr.VarRef(token.value, token.line)
            }
            TokenType.LPAREN -> { 
                advance()
                val expr = parseExpression()
                expect(TokenType.RPAREN)
                expr 
            }
            else -> throw TesseractError(R.string.err_unexpected_token, token.line, emptyList(), token.value)
        }
    }
}

// ============================================================================
// 5. EVALUATOR (Interpreter)
// ============================================================================

/**
 * Executes the Abstract Syntax Tree (AST).
 * Manages variable scope, function calls, and enforces security limits 
 * (max recursion depth, max loop iterations, execution timeouts).
 */
class Evaluator(private val context: Context) {
    private val env = mutableMapOf<String, TValue>()
    private val userFunctions = mutableMapOf<String, Stmt.FunctionDef>()
    private val callStack = mutableListOf<String>()
    private var recursionDepth = 0
    private var totalUserFunctionCalls = 0L 
    
    private val secureRandom = SecureRandom()
    private val standardRandom = Random()

    /** Initializes the environment with built-in mathematical and physical constants. */
    private fun resetEnvironment() {
        env.clear()
        userFunctions.clear()
        recursionDepth = 0
        totalUserFunctionCalls = 0L 
        
        env["PI"] = TValue.TNum(PI)
        env["E"] = TValue.TNum(E)
        env["PHI"] = TValue.TNum(1.618033988749895)
        env["TRUE"] = TValue.TInt(1)
        env["FALSE"] = TValue.TInt(0)

        // Physical constants
        env["C"] = TValue.TNum(299792458.0)
        env["G"] = TValue.TNum(9.81)
        env["EARTH_R"] = TValue.TNum(6371000.0)
        env["AVOGADRO"] = TValue.TNum(6.02214076e23)
        
        // Data units
        env["KB"] = TValue.TNum(1024.0)
        env["MB"] = TValue.TNum(1048576.0)
        env["GB"] = TValue.TNum(1073741824.0)
        
        // Time units (in seconds or milliseconds)
        env["MS_IN_SEC"] = TValue.TNum(1000.0)
        env["SEC_IN_MIN"] = TValue.TNum(60.0)
        env["MIN_IN_HOUR"] = TValue.TNum(60.0)
        env["HOUR_IN_DAY"] = TValue.TNum(24.0)
        env["SEC_IN_HOUR"] = TValue.TNum(3600.0)
        env["SEC_IN_DAY"] = TValue.TNum(86400.0)
        env["MS_IN_MIN"] = TValue.TNum(60000.0)
        env["MS_IN_HOUR"] = TValue.TNum(3600000.0)
        env["MS_IN_DAY"] = TValue.TNum(86400000.0)
        env["WEEK"] = TValue.TNum(7.0)
        env["MONTH"] = TValue.TNum(30.0)
        env["MONTH_WITH_DAY"] = TValue.TNum(31.0)
        env["YEAR"] = TValue.TNum(365.0)
        
        // Length units (in meters)
        env["MM"] = TValue.TNum(0.001)
        env["CM"] = TValue.TNum(0.01)
        env["M"] = TValue.TNum(1.0)
        env["KM"] = TValue.TNum(1000.0)
        env["INCH"] = TValue.TNum(0.0254)
        env["FOOT"] = TValue.TNum(0.3048)
        env["YARD"] = TValue.TNum(0.9144)
        env["MILE"] = TValue.TNum(1609.344)
        env["NAUTICAL_MILE"] = TValue.TNum(1852.0)
    }

    fun evaluate(statements: List<Stmt>, constantOverrides: Map<String, String>): String {
        resetEnvironment()
        
        // Apply user-provided parameter overrides before execution
        constantOverrides.forEach { (key, value) ->
            env[key] = if (value.contains('.')) TValue.TNum(value.toDoubleOrNull() ?: 0.0) else TValue.TInt(value.toLongOrNull() ?: 0L)
        }

        // First pass: register all function definitions
        for (stmt in statements) {
            if (stmt is Stmt.FunctionDef) {
                userFunctions[stmt.name] = stmt
            }
        }

        val results = mutableListOf<String>()
        var exitDelayMs: Long? = null

        try {
            // Second pass: execute statements
            for (stmt in statements) {
                when (stmt) {
                    is Stmt.SeparatorStmt -> results.add("---")
                    is Stmt.FunctionDef -> {} // Already registered
                    is Stmt.Assignment -> {
                        // Prevent overriding constants that were explicitly passed as overrides
                        if (!constantOverrides.containsKey(stmt.name)) {
                            env[stmt.name] = eval(stmt.value)
                        }
                    }
                    else -> {
                        val res = evalStmt(stmt)
                        if (res != null) results.add(res.displayString())
                    }
                }
            }
        } catch (e: ReturnValue) {
            if (e.value != null) results.add(e.value.displayString())
        } catch (e: TesseractExitCommand) {
            exitDelayMs = e.delayMs
        } catch (e: TesseractOpenActCommand) {
            throw e // Bubble up to Activity to handle Intent
        }
        
        val output = if (results.isEmpty()) context.getString(R.string.result_void) else results.joinToString("\n")
        
        return if (exitDelayMs != null) {
            "__TESSERACT_EXIT__:$exitDelayMs\n$output"
        } else {
            context.getString(R.string.result_success, output)
        }
    }

    private fun evalStmt(node: Stmt): TValue? = when (node) {
        is Stmt.AssertStmt -> {
            val res = eval(node.condition)
            val isTrue = when (res) { is TValue.TNum -> res.value != 0.0; is TValue.TInt -> res.value != 0L; else -> false }
            if (!isTrue) throw TesseractError(R.string.err_assert_false, node.line, callStack.toList())
            null
        }
        is Stmt.ReturnStmt -> throw ReturnValue(if (node.value != null) eval(node.value) else null)
        is Stmt.Assignment -> { env[node.name] = eval(node.value); null }
        is Stmt.ExitStmt -> throw TesseractExitCommand(node.delayMs)
        is Stmt.WhileStmt -> {
            var iterations = 0
            val startTime = System.currentTimeMillis()
            
            while (true) {
                // SECURITY: Prevent infinite loops from freezing the app
                if (System.currentTimeMillis() - startTime > 3000) {
                    throw TesseractError(R.string.err_while_timeout, node.line, callStack.toList())
                }

                val condVal = eval(node.cond)
                val isTrue = when (condVal) { is TValue.TNum -> condVal.value != 0.0; is TValue.TInt -> condVal.value != 0L; else -> false }
                if (!isTrue) break
                
                for (stmt in node.body) evalStmt(stmt)
                
                // SECURITY: Hard limit on loop iterations
                if (++iterations > 1_000_000) throw TesseractError(R.string.err_while_iterations, node.line, callStack.toList())
            }
            null
        }
        is Stmt.ExprStmt -> eval(node.expr)
        is Stmt.FunctionDef, is Stmt.SeparatorStmt -> null
    }

    private fun eval(node: Expr): TValue = when (node) {
        is Expr.NumLit -> TValue.TNum(node.value)
        is Expr.IntLit -> TValue.TInt(node.value)
        is Expr.StrLit -> TValue.TStr(node.value)
        is Expr.VarRef -> env[node.name] ?: throw TesseractError(R.string.err_var_undefined, node.line, callStack.toList(), node.name)
        is Expr.UnaryOp -> {
            val v = eval(node.operand).toDouble()
            MathGuard.checkOverflow(v, node.line)
            if (node.op == TokenType.MINUS) TValue.TNum(-v) else TValue.TNum(v)
        }
        is Expr.IfElse -> {
            val condVal = eval(node.cond)
            val isTrue = when (condVal) { is TValue.TNum -> condVal.value != 0.0; is TValue.TInt -> condVal.value != 0L; else -> false }
            if (isTrue) eval(node.thenExpr) else eval(node.elseExpr)
        }
        is Expr.BinaryOp -> evalBinaryOp(node)
        is Expr.Pipeline -> evalPipeline(node)
        is Expr.FuncCall -> evalFuncCall(node)
    }

    private fun evalBinaryOp(node: Expr.BinaryOp): TValue {
        val left = eval(node.left)
        val right = eval(node.right)
        
        if (node.op == TokenType.AND) {
            val l = left.toDouble() != 0.0
            val r = right.toDouble() != 0.0
            return if (l && r) TValue.TInt(1) else TValue.TInt(0)
        }
        if (node.op == TokenType.OR) {
            val l = left.toDouble() != 0.0
            val r = right.toDouble() != 0.0
            return if (l || r) TValue.TInt(1) else TValue.TInt(0)
        }

        if (node.op in listOf(TokenType.GT, TokenType.LT, TokenType.GTE, TokenType.LTE, TokenType.EQ, TokenType.NEQ)) {
            val res = when (node.op) {
                TokenType.GT -> left.toDouble() > right.toDouble()
                TokenType.LT -> left.toDouble() < right.toDouble()
                TokenType.GTE -> left.toDouble() >= right.toDouble()
                TokenType.LTE -> left.toDouble() <= right.toDouble()
                TokenType.EQ -> abs(left.toDouble() - right.toDouble()) < 1e-9 // Epsilon comparison for floats
                TokenType.NEQ -> abs(left.toDouble() - right.toDouble()) >= 1e-9
                else -> false
            }
            return if (res) TValue.TInt(1) else TValue.TInt(0)
        }

        if (node.op == TokenType.XOR) {
            if (left is TValue.TInt && right is TValue.TInt) return TValue.TInt(left.value xor right.value)
            throw TesseractError(R.string.err_xor_int_only, node.line, callStack.toList())
        }

        return when (node.op) {
            TokenType.PLUS -> {
                if (left is TValue.TStr || right is TValue.TStr) {
                    TValue.TStr(left.displayString() + right.displayString()) // String concatenation
                } else {
                    TValue.TNum(left.toDouble() + right.toDouble())
                }
            }
            TokenType.MINUS -> TValue.TNum(left.toDouble() - right.toDouble())
            TokenType.MUL -> TValue.TNum(left.toDouble() * right.toDouble())
            TokenType.DIV -> { MathGuard.checkDivision(right, node.line); TValue.TNum(left.toDouble() / right.toDouble()) }
            TokenType.INT_DIV -> { MathGuard.checkDivision(right, node.line); TValue.TInt(left.toLong() / right.toLong()) }
            TokenType.MOD -> { MathGuard.checkDivision(right, node.line); TValue.TNum(left.toDouble() % right.toDouble()) }
            TokenType.POW -> { 
                val r = left.toDouble().pow(right.toDouble())
                MathGuard.checkOverflow(r, node.line)
                TValue.TNum(r) 
            }
            else -> throw TesseractError(R.string.err_unknown_operator, node.line, emptyList(), node.op)
        }
    }

    private fun evalPipeline(node: Expr.Pipeline): TValue {
        val leftVal = eval(node.left)
        // Convert the left value into a literal expression to pass as the first argument
        val tempLit = when (leftVal) {
            is TValue.TNum -> Expr.NumLit(leftVal.value, node.line)
            is TValue.TInt -> Expr.IntLit(leftVal.toLong(), node.line)
            is TValue.TStr -> Expr.StrLit(leftVal.value, node.line)
        }
        return if (node.right is Expr.FuncCall) {
            eval(Expr.FuncCall(node.right.name, listOf(tempLit) + node.right.args, node.line))
        } else if (node.right is Expr.VarRef) {
            eval(Expr.FuncCall(node.right.name, listOf(tempLit), node.line))
        } else {
            throw TesseractError(R.string.err_pipeline_func_expected, node.line)
        }
    }

    private fun evalFuncCall(node: Expr.FuncCall): TValue {
        val userFunc = userFunctions[node.name]
        if (userFunc != null) {
            // SECURITY: Prevent infinite recursion and stack overflow
            totalUserFunctionCalls++
            if (totalUserFunctionCalls > 1_000_000) {
                throw TesseractError(R.string.err_global_call_limit, node.line, callStack.toList())
            }

            if (++recursionDepth > 2000) {
                throw TesseractError(R.string.err_recursion_depth, node.line, callStack.toList())
            }
            
            callStack.add("${node.name}()")
            
            if (node.args.size != userFunc.params.size) throw TesseractError(R.string.err_args_mismatch, node.line, callStack.toList(), node.name)
            
            // Save current environment to restore after function execution (lexical scoping)
            val savedEnv = env.toMap()
            for (i in userFunc.params.indices) env[userFunc.params[i]] = eval(node.args[i])
            
            val result = try {
                var res: TValue? = null
                for (stmt in userFunc.body) res = evalStmt(stmt)
                res ?: TValue.TInt(0) // Default return 0 if no explicit return
            } catch (e: ReturnValue) {
                e.value ?: TValue.TInt(0)
            } finally {
                // Restore environment and cleanup stack
                env.clear()
                env.putAll(savedEnv)
                callStack.removeLast()
                recursionDepth--
            }
            return result
        }

        // Built-in functions evaluation
        callStack.add("${node.name}()")
        val args = node.args.map { eval(it) }
        val result = try {
            when (node.name) {
                "open_act" -> {
                    if (args.isEmpty() || args[0] !is TValue.TStr) {
                        throw TesseractError(R.string.err_open_act_string, node.line)
                    }
                    throw TesseractOpenActCommand((args[0] as TValue.TStr).value)
                }
                "set_seed" -> {
                    if (args.size != 1) throw TesseractError(R.string.err_set_seed_args, node.line)
                    standardRandom.setSeed(args[0].toLong())
                    TValue.TInt(1)
                }
                "random" -> {
                    if (args.isNotEmpty()) throw TesseractError(R.string.err_random_no_args, node.line)
                    TValue.TNum(standardRandom.nextDouble())
                }
                "random_int" -> {
                    if (args.size != 2) throw TesseractError(R.string.err_random_int_args, node.line)
                    val min = args[0].toLong()
                    val max = args[1].toLong()
                    if (min > max) throw TesseractError(R.string.err_random_int_min_max, node.line)
                    val range = (max - min + 1).toInt()
                    if (range <= 0) throw TesseractError(R.string.err_random_int_range, node.line)
                    TValue.TInt(min + standardRandom.nextInt(range))
                }
                "secure_random" -> {
                    if (args.isNotEmpty()) throw TesseractError(R.string.err_secure_random_no_args, node.line)
                    TValue.TNum(secureRandom.nextDouble())
                }
                "secure_random_int" -> {
                    if (args.size != 2) throw TesseractError(R.string.err_secure_random_int_args, node.line)
                    val min = args[0].toLong()
                    val max = args[1].toLong()
                    if (min > max) throw TesseractError(R.string.err_secure_random_int_min_max, node.line)
                    val range = (max - min + 1).toInt()
                    TValue.TInt(min + secureRandom.nextInt(range))
                }
                "char_at" -> {
                    if (args.size != 2) throw TesseractError(R.string.err_char_at_args, node.line)
                    val strVal = args[0]
                    val idx = args[1].toLong().toInt()
                    if (strVal is TValue.TStr) {
                        if (idx < 0 || idx >= strVal.value.length) throw TesseractError(R.string.err_char_at_bounds, node.line)
                        TValue.TStr(strVal.value[idx].toString())
                    } else {
                        throw TesseractError(R.string.err_char_at_first_string, node.line)
                    }
                }
                "sum" -> TValue.TNum(if (args.isEmpty()) 0.0 else args.sumOf { it.toDouble() })
                "avg" -> {
                    if (args.isEmpty()) throw TesseractError(R.string.err_avg_args, node.line)
                    TValue.TNum(args.sumOf { it.toDouble() } / args.size)
                }
                "max_val" -> {
                    if (args.isEmpty()) throw TesseractError(R.string.err_max_val_args, node.line)
                    TValue.TNum(args.maxOf { it.toDouble() })
                }
                "min_val" -> {
                    if (args.isEmpty()) throw TesseractError(R.string.err_min_val_args, node.line)
                    TValue.TNum(args.minOf { it.toDouble() })
                }
                "count" -> TValue.TNum(args.size.toDouble())
                "median" -> {
                    if (args.isEmpty()) throw TesseractError(R.string.err_median_args, node.line)
                    val sorted = args.map { it.toDouble() }.sorted()
                    val mid = sorted.size / 2
                    val res = if (sorted.size % 2 == 0) {
                        (sorted[mid - 1] + sorted[mid]) / 2.0
                    } else {
                        sorted[mid]
                    }
                    TValue.TNum(res)
                }
                "toNum" -> TValue.TNum(args[0].toDouble())
                "toInt" -> TValue.TInt(args[0].toLong())
                "log2" -> { MathGuard.checkLogarithm(args[0], node.line); TValue.TNum(ln(args[0].toDouble()) / ln(2.0)) }
                "ln" -> { MathGuard.checkLogarithm(args[0], node.line); TValue.TNum(ln(args[0].toDouble())) }
                "log10" -> { MathGuard.checkLogarithm(args[0], node.line); TValue.TNum(log10(args[0].toDouble())) }
                "sqrt" -> { MathGuard.checkRoot(args[0], TValue.TNum(2.0), node.line); TValue.TNum(sqrt(args[0].toDouble())) }
                "cbrt" -> TValue.TNum(cbrt(args[0].toDouble()))
                "root" -> { MathGuard.checkRoot(args[0], args[1], node.line); TValue.TNum(args[0].toDouble().pow(1.0 / args[1].toDouble())) }
                "pow" -> { val r = args[0].toDouble().pow(args[1].toDouble()); MathGuard.checkOverflow(r, node.line); TValue.TNum(r) }
                "exp" -> { val r = exp(args[0].toDouble()); MathGuard.checkOverflow(r, node.line); TValue.TNum(r) }
                "sin" -> TValue.TNum(sin(args[0].toDouble()))
                "cos" -> TValue.TNum(cos(args[0].toDouble()))
                "tan" -> { val rad = args[0].toDouble(); if (abs(cos(rad)) < 1e-10) throw TesseractError(R.string.err_tan_infinity, node.line, callStack.toList()); TValue.TNum(tan(rad)) }
                "asin" -> { if (args[0].toDouble() !in -1.0..1.0) throw TesseractError(R.string.err_asin_domain, node.line, callStack.toList()); TValue.TNum(asin(args[0].toDouble())) }
                "acos" -> { if (args[0].toDouble() !in -1.0..1.0) throw TesseractError(R.string.err_acos_domain, node.line, callStack.toList()); TValue.TNum(acos(args[0].toDouble())) }
                "atan" -> TValue.TNum(atan(args[0].toDouble()))
                "sinh" -> TValue.TNum(sinh(args[0].toDouble()))
                "cosh" -> TValue.TNum(cosh(args[0].toDouble()))
                "tanh" -> TValue.TNum(tanh(args[0].toDouble()))
                "abs" -> when (args[0]) { is TValue.TNum -> TValue.TNum(abs(args[0].toDouble())); is TValue.TInt -> TValue.TInt(abs(args[0].toLong())); else -> throw TesseractError(R.string.err_abs_number, node.line) }
                "floor" -> TValue.TInt(floor(args[0].toDouble()).toLong())
                "ceil" -> TValue.TInt(ceil(args[0].toDouble()).toLong())
                "round" -> TValue.TInt(round(args[0].toDouble()).toLong())
                "min" -> if (args[0].toDouble() < args[1].toDouble()) args[0] else args[1]
                "max" -> if (args[0].toDouble() > args[1].toDouble()) args[0] else args[1]
                "rev" -> when (val arg = args[0]) {
                    is TValue.TInt -> TValue.TInt(arg.value.toString().reversed().toLongOrNull() ?: 0L)
                    is TValue.TStr -> TValue.TStr(arg.value.reversed())
                    else -> throw TesseractError(R.string.err_rev_type, node.line)
                }
                "exit" -> {
                    val delay = if (args.isNotEmpty()) args[0].toLong() else 0L
                    throw TesseractExitCommand(delay)
                }
                else -> throw TesseractError(R.string.err_unknown_func, node.line, callStack.toList(), node.name)
            }
        } finally {
            callStack.removeLast()
        }
        if (result is TValue.TNum) MathGuard.checkOverflow(result.value, node.line)
        return result
    }
}

// ============================================================================
// 6. ENGINE (Entry Point)
// ============================================================================

/**
 * Main facade for the Perceptron/Tesseract interpreter.
 * Orchestrates Lexing, Parsing, and Evaluation, and gracefully formats errors for the UI.
 */
object TesseractEngine1 {
    fun evaluate(context: Context, script: String, constantOverrides: Map<String, String> = emptyMap()): String {
        return try {
            val lexer = Lexer(script)
            val tokens = lexer.tokenize()
            val parser = Parser(tokens)
            val ast = parser.parse()
            val evaluator = Evaluator(context)
            evaluator.evaluate(ast, constantOverrides)
            
        } catch (e: TesseractError) {
            // Format interpreter errors with line numbers and call stack
            val msg = context.getString(e.messageResId, *e.formatArgs)
            val sb = StringBuilder()
            sb.appendLine(context.getString(R.string.error_perceptron_line, e.line, msg))
            if (e.callStack.isNotEmpty()) {
                sb.appendLine(context.getString(R.string.error_call_stack))
                e.callStack.forEachIndexed { index, trace -> 
                    sb.appendLine("   ${" ".repeat(index)}-> $trace") 
                }
            }
            sb.toString().trim()
        } catch (e: StackOverflowError) {
            // Catch JVM-level stack overflow as a fallback security measure
            context.getString(R.string.error_stack_overflow)
        } catch (e: TesseractOpenActCommand) {
            throw e // Allow Activity to handle external intents
        } catch (e: Exception) {
            // Catch any other unexpected crashes
            context.getString(R.string.error_critical, e.message ?: "Unknown")
        }
    }
}
