package com.syntricdb.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for a single WHERE-clause conjunct. Used by {@link WhereExprParser} to build a
 * general boolean expression tree instead of matching the whole clause with one regex.
 */
class SQLTokenizer {

    enum TokenType { IDENT, NUMBER, STRING, OP, LPAREN, RPAREN, COMMA, DOT, EOF }

    static class Token {
        final TokenType type;
        final String text;

        Token(TokenType type, String text) {
            this.type = type;
            this.text = text;
        }

        boolean isKeyword(String kw) {
            return type == TokenType.IDENT && text.equalsIgnoreCase(kw);
        }

        @Override
        public String toString() {
            return type + "('" + text + "')";
        }
    }

    private final String src;
    private int pos = 0;

    SQLTokenizer(String src) {
        this.src = src;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= src.length()) {
                tokens.add(new Token(TokenType.EOF, ""));
                break;
            }
            char c = src.charAt(pos);
            if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                pos++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                pos++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                pos++;
            } else if (c == '\'' || c == '"') {
                tokens.add(readString(c));
            } else if (c == '!' && peek(1) == '=') {
                tokens.add(new Token(TokenType.OP, "!="));
                pos += 2;
            } else if (c == '<' && peek(1) == '>') {
                tokens.add(new Token(TokenType.OP, "!="));
                pos += 2;
            } else if (c == '<' && peek(1) == '=') {
                tokens.add(new Token(TokenType.OP, "<="));
                pos += 2;
            } else if (c == '>' && peek(1) == '=') {
                tokens.add(new Token(TokenType.OP, ">="));
                pos += 2;
            } else if (c == '=') {
                tokens.add(new Token(TokenType.OP, "="));
                pos++;
            } else if (c == '<') {
                tokens.add(new Token(TokenType.OP, "<"));
                pos++;
            } else if (c == '>') {
                tokens.add(new Token(TokenType.OP, ">"));
                pos++;
            } else if (Character.isDigit(c) || (c == '-' && Character.isDigit(peek(1)))) {
                tokens.add(readNumber());
            } else if (Character.isLetter(c) || c == '_') {
                tokens.add(readIdent());
            } else if (c == '.') {
                tokens.add(new Token(TokenType.DOT, "."));
                pos++;
            } else {
                throw new IllegalArgumentException("Unexpected character '" + c + "' in WHERE clause: " + src);
            }
        }
        return tokens;
    }

    private char peek(int ahead) {
        int p = pos + ahead;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private Token readString(char quote) {
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == quote) {
                if (peek(1) == quote) { // doubled quote = one literal quote (SQL-standard escaping)
                    sb.append(quote);
                    pos += 2;
                    continue;
                }
                pos++;
                return new Token(TokenType.STRING, sb.toString());
            }
            sb.append(c);
            pos++;
        }
        throw new IllegalArgumentException("Unterminated string literal in WHERE clause: " + src);
    }

    private Token readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        return new Token(TokenType.NUMBER, src.substring(start, pos));
    }

    private Token readIdent() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        return new Token(TokenType.IDENT, src.substring(start, pos));
    }
}
