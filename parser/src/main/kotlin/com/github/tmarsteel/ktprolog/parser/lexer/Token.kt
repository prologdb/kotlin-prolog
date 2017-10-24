package com.github.tmarsteel.ktprolog.parser.lexer

import com.github.tmarsteel.ktprolog.parser.source.SourceLocationRange

enum class TokenType {
    IDENTIFIER,
    OPERATOR,
    NUMERIC_LITERAL
}

enum class Operator(val text: String) {
    PARANT_OPEN("("),
    PARANT_CLOSE(")"),
    FULL_STOP("."),
    SEMICOLON(";"),
    COMMA(","),
    HEAD_QUERY_SEPARATOR(":-")
}

val DECIMAL_SEPARATOR: Char = '.'

sealed class Token(val type: TokenType, val location: SourceLocationRange)

class IdentifierToken(val textContent: String, location: SourceLocationRange) : Token(TokenType.IDENTIFIER, location) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentifierToken) return false

        if (textContent != other.textContent) return false

        return true
    }

    override fun hashCode(): Int {
        return textContent.hashCode()
    }
}
class OperatorToken(val operator: Operator, location: SourceLocationRange): Token(TokenType.OPERATOR, location) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OperatorToken) return false

        if (operator != other.operator) return false

        return true
    }

    override fun hashCode(): Int {
        return operator.hashCode()
    }
}
class NumericLiteralToken(val number: Number, location: SourceLocationRange): Token(TokenType.NUMERIC_LITERAL, location) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NumericLiteralToken) return false

        if (number != other.number) return false

        return true
    }

    override fun hashCode(): Int {
        return number.hashCode()
    }
}
