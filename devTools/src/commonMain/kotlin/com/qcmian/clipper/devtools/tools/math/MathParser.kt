package com.qcmian.clipper.devtools.tools.math

/**
 * 数学表达式的词法与语法分析。
 *
 * 手写而非引库：这套语法小且稳定（见下面每一层的注释），两百行就写完了，换来的是**报错文案完全
 * 可控**——每一处错都能带上出错位置，而第三方求值库给的多半只有一句英文。
 *
 * 文法（优先级从低到高）：
 * ```
 * 表达式 := 项 (('+' | '-') 项)*
 * 项     := 一元 (('*' | '/' | '%') 一元)*
 * 一元   := ('+' | '-') 一元 | 幂
 * 幂     := 后缀 ('^' 一元)?        // 右结合，且指数允许带一元负号：2^-3
 * 后缀   := 基本 ('deg' | '°')*
 * 基本   := 数字 | 名字 [ '(' 参数表 ')' ] | '(' 表达式 ')'
 * ```
 * 「项」用 `parseProduct` 实现，「一元」压在「幂」之上，因此 `-2^2` 是 `-(2^2)`（多数计算器的约定）。
 *
 * 角度单位写在**表达式里**（`90deg` / `90°`），不由界面另给一个开关：`sin(90)` 究竟想表达什么，
 * 光看数字永远说不清，不如让写的人自己标出来。
 */
internal object MathParser {
    /** 解析 [text]；失败抛 [MathError]（带出错位置）。 */
    fun parse(text: String): MathExpr = Parser(tokenize(text)).parseAll()
}

/** 角度后缀折成的那个函数名；与 [MathEvaluator] 的函数表约定一致。 */
private const val DegreeFunction = "deg"

/** 可作角度后缀的名字：`90deg` 与 `90°` 等价。 */
private val UnitSuffixes = setOf("deg", "\u00B0")

/** 词法单元。[number] 只在 [Kind.Number] 时有意义。 */
private class Token(
    val kind: Kind,
    val text: String,
    val position: Int,
    val number: Double = 0.0,
) {
    enum class Kind {
        Number, Name,
        Plus, Minus, Star, Slash, Percent, Caret,
        LeftParen, RightParen, Comma,
        End,
    }
}

private fun tokenize(text: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++

            c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) ->
                i = readNumber(text, i, tokens)

            c.isLetter() || c == '_' -> {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                tokens.add(Token(Token.Kind.Name, text.substring(start, i), start))
            }

            // 角度符号：当成一个名字（`°`），与 `deg` 一样充当角度后缀。
            c == '\u00B0' -> {
                tokens.add(Token(Token.Kind.Name, "\u00B0", i))
                i++
            }

            else -> {
                val kind = when (c) {
                    '+' -> Token.Kind.Plus
                    '-' -> Token.Kind.Minus
                    '*' -> Token.Kind.Star
                    '/' -> Token.Kind.Slash
                    '%' -> Token.Kind.Percent
                    '^' -> Token.Kind.Caret
                    '(' -> Token.Kind.LeftParen
                    ')' -> Token.Kind.RightParen
                    ',' -> Token.Kind.Comma
                    else -> throw MathError("不认识的字符 '$c'", i)
                }
                tokens.add(Token(kind, c.toString(), i))
                i++
            }
        }
    }
    tokens.add(Token(Token.Kind.End, "", text.length))
    return tokens
}

/**
 * 读一个数字：支持十进制（含小数点与 `e` 指数）、以及 `0x` / `0b` / `0o` 前缀的整数。
 *
 * 指数只在小数点之后、且 `e` 后面确实跟着数字时才吞掉：`2e` 会被拆成 `2` 与名字 `e`，
 * 而不是把 `e` 当成指数的开头报一个莫名其妙的错。
 */
private fun readNumber(text: String, start: Int, tokens: MutableList<Token>): Int {
    var i = start
    if (text[i] == '0' && i + 1 < text.length) {
        val radix = when (text[i + 1].lowercaseChar()) {
            'x' -> 16
            'b' -> 2
            'o' -> 8
            else -> 0
        }
        if (radix != 0) {
            var j = i + 2
            while (j < text.length && text[j].digitToIntOrNull(radix) != null) j++
            if (j == i + 2) throw MathError("'0${text[i + 1]}' 后面要跟数字", i)
            val value = text.substring(i + 2, j).toLongOrNull(radix)
                ?: throw MathError("这个数字超出可表示范围", i)
            tokens.add(Token(Token.Kind.Number, text.substring(i, j), i, value.toDouble()))
            return j
        }
    }

    while (i < text.length && text[i].isDigit()) i++
    if (i < text.length && text[i] == '.') {
        i++
        while (i < text.length && text[i].isDigit()) i++
    }
    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
        var j = i + 1
        if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
        if (j < text.length && text[j].isDigit()) {
            while (j < text.length && text[j].isDigit()) j++
            i = j
        }
    }

    val literal = text.substring(start, i)
    val value = literal.toDoubleOrNull() ?: throw MathError("这个数字看不懂：$literal", start)
    tokens.add(Token(Token.Kind.Number, literal, start, value))
    return i
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0

    private val current: Token get() = tokens[index]

    private fun advance(): Token = tokens[index++]

    private fun match(kind: Token.Kind): Boolean {
        if (current.kind != kind) return false
        index++
        return true
    }

    private fun expect(kind: Token.Kind, message: String) {
        if (current.kind != kind) throw MathError(message, current.position)
        index++
    }

    fun parseAll(): MathExpr {
        if (current.kind == Token.Kind.End) throw MathError("表达式是空的", 0)
        val expr = parseSum()
        if (current.kind != Token.Kind.End) {
            throw MathError("这里多出一段看不懂的内容：'${current.text}'", current.position)
        }
        return expr
    }

    private fun parseSum(): MathExpr {
        var left = parseProduct()
        while (current.kind == Token.Kind.Plus || current.kind == Token.Kind.Minus) {
            val op = advance()
            val right = parseProduct()
            left = MathExpr.Binary(if (op.kind == Token.Kind.Plus) '+' else '-', left, right, op.position)
        }
        return left
    }

    private fun parseProduct(): MathExpr {
        var left = parseUnary()
        while (true) {
            val op = when (current.kind) {
                Token.Kind.Star -> '*'
                Token.Kind.Slash -> '/'
                Token.Kind.Percent -> '%'
                else -> return left
            }
            val token = advance()
            val right = parseUnary()
            left = MathExpr.Binary(op, left, right, token.position)
        }
    }

    private fun parseUnary(): MathExpr {
        if (current.kind == Token.Kind.Plus || current.kind == Token.Kind.Minus) {
            val op = advance()
            val operand = parseUnary()
            return MathExpr.Unary(if (op.kind == Token.Kind.Plus) '+' else '-', operand, op.position)
        }
        return parsePower()
    }

    private fun parsePower(): MathExpr {
        val base = parsePostfix()
        if (current.kind != Token.Kind.Caret) return base
        val op = advance()
        // 右结合，且指数用 `parseUnary`：`2^3^2` = 2^(3^2)，`2^-3` 也成立。
        return MathExpr.Binary('^', base, parseUnary(), op.position)
    }

    /**
     * 角度后缀：`90deg`、`90°`、`(45 + 45)deg`。
     *
     * 折成一次 `deg(...)` 调用——求值那边因此不必认识「单位」这回事，`deg(x)` 本来就是一个把角度
     * 换成弧度的普通函数。这一层压在 `^` 之下，`2deg^2` 于是算作 `(2deg)^2`。
     */
    private fun parsePostfix(): MathExpr {
        var expr = parsePrimary()
        while (current.kind == Token.Kind.Name && current.text.lowercase() in UnitSuffixes) {
            val unit = advance()
            expr = MathExpr.Call(DegreeFunction, listOf(expr), unit.position)
        }
        return expr
    }

    private fun parsePrimary(): MathExpr {
        val token = current
        return when (token.kind) {
            Token.Kind.Number -> {
                advance()
                MathExpr.Number(token.number)
            }

            Token.Kind.Name -> {
                advance()
                if (!match(Token.Kind.LeftParen)) return MathExpr.Name(token.text, token.position)
                val args = ArrayList<MathExpr>()
                if (current.kind != Token.Kind.RightParen) {
                    args.add(parseSum())
                    while (match(Token.Kind.Comma)) args.add(parseSum())
                }
                expect(Token.Kind.RightParen, "缺少右括号 )")
                MathExpr.Call(token.text, args, token.position)
            }

            Token.Kind.LeftParen -> {
                advance()
                val inner = parseSum()
                expect(Token.Kind.RightParen, "缺少右括号 )")
                inner
            }

            else -> throw MathError("这里应该是一个数字、常量或左括号 (", token.position)
        }
    }
}
