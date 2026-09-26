package com.syntricdb.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for a WHERE-clause boolean expression: AND/OR/NOT, parentheses,
 * comparisons (=, !=, &gt;, &lt;, &gt;=, &lt;=), LIKE, IN, BETWEEN, and IS [NOT] NULL.
 *
 * <pre>
 * orExpr        := andExpr (OR andExpr)*
 * andExpr       := notExpr (AND notExpr)*
 * notExpr       := NOT notExpr | primary
 * primary       := '(' orExpr ')' | comparison
 * comparison    := qualifiedIdent ( op value
 *                                 | [NOT] LIKE string
 *                                 | [NOT] IN '(' value (',' value)* ')'
 *                                 | [NOT] BETWEEN value AND value
 *                                 | IS [NOT] NULL )
 * </pre>
 *
 * Does not understand SIMILAR TO / MATCH(...) — those are carved out of the raw WHERE
 * text as separate top-level conjuncts by {@link SQLParser} before this parser ever sees
 * the remaining text, matching the engine's existing vector/full-text search execution model.
 */
class WhereExprParser {
    private final List<SQLTokenizer.Token> tokens;
    private int pos = 0;

    private WhereExprParser(List<SQLTokenizer.Token> tokens) {
        this.tokens = tokens;
    }

    static AST.WhereExpr parse(String text) {
        List<SQLTokenizer.Token> tokens = new SQLTokenizer(text).tokenize();
        WhereExprParser parser = new WhereExprParser(tokens);
        AST.WhereExpr expr = parser.parseOr();
        if (parser.current().type != SQLTokenizer.TokenType.EOF) {
            throw new IllegalArgumentException("Unexpected token " + parser.current() + " in WHERE clause: " + text);
        }
        return expr;
    }

    private SQLTokenizer.Token current() { return tokens.get(pos); }
    private SQLTokenizer.Token advance() { return tokens.get(pos++); }
    private boolean isKeyword(String kw) { return current().isKeyword(kw); }

    private AST.WhereExpr parseOr() {
        AST.WhereExpr left = parseAnd();
        List<AST.WhereExpr> operands = null;
        while (isKeyword("OR")) {
            advance();
            if (operands == null) {
                operands = new ArrayList<>();
                operands.add(left);
            }
            operands.add(parseAnd());
        }
        return operands == null ? left : new AST.OrExpr(operands);
    }

    private AST.WhereExpr parseAnd() {
        AST.WhereExpr left = parseNot();
        List<AST.WhereExpr> operands = null;
        while (isKeyword("AND")) {
            advance();
            if (operands == null) {
                operands = new ArrayList<>();
                operands.add(left);
            }
            operands.add(parseNot());
        }
        return operands == null ? left : new AST.AndExpr(operands);
    }

    private AST.WhereExpr parseNot() {
        if (isKeyword("NOT")) {
            advance();
            return new AST.NotExpr(parseNot());
        }
        return parsePrimary();
    }

    private AST.WhereExpr parsePrimary() {
        if (current().type == SQLTokenizer.TokenType.LPAREN) {
            advance();
            AST.WhereExpr inner = parseOr();
            if (current().type != SQLTokenizer.TokenType.RPAREN) {
                throw new IllegalArgumentException("Expected ')' in WHERE clause.");
            }
            advance();
            return inner;
        }
        return parseComparison();
    }

    private AST.WhereExpr parseComparison() {
        String column = parseQualifiedIdentifier();

        boolean negated = false;
        if (isKeyword("NOT")) {
            advance();
            negated = true;
        }

        if (isKeyword("LIKE")) {
            advance();
            return new AST.LikeExpr(column, parseStringLiteral("LIKE"), negated);
        }
        if (isKeyword("IN")) {
            advance();
            return new AST.InExpr(column, parseValueList(), negated);
        }
        if (isKeyword("BETWEEN")) {
            advance();
            Object low = parseValue();
            expectKeyword("AND");
            Object high = parseValue();
            return new AST.BetweenExpr(column, low, high, negated);
        }
        if (negated) {
            throw new IllegalArgumentException("Expected LIKE, IN, or BETWEEN after NOT in WHERE clause, found " + current());
        }
        if (isKeyword("IS")) {
            advance();
            boolean isNotNull = false;
            if (isKeyword("NOT")) {
                advance();
                isNotNull = true;
            }
            expectKeyword("NULL");
            return new AST.IsNullExpr(column, isNotNull);
        }

        if (current().type != SQLTokenizer.TokenType.OP) {
            throw new IllegalArgumentException("Expected a comparison operator after '" + column + "' in WHERE clause, found " + current());
        }
        String op = advance().text;
        Object value = parseValue();
        return new AST.ComparisonExpr(column, op, value);
    }

    private void expectKeyword(String kw) {
        if (!isKeyword(kw)) {
            throw new IllegalArgumentException("Expected keyword '" + kw + "' in WHERE clause, found " + current());
        }
        advance();
    }

    private String parseQualifiedIdentifier() {
        if (current().type != SQLTokenizer.TokenType.IDENT) {
            throw new IllegalArgumentException("Expected a column name in WHERE clause, found " + current());
        }
        String name = advance().text;
        // Strip any "alias." qualifier(s), e.g. Hibernate's "p1_0.id" -> "id".
        while (current().type == SQLTokenizer.TokenType.DOT) {
            advance();
            if (current().type != SQLTokenizer.TokenType.IDENT) {
                throw new IllegalArgumentException("Expected a column name after '.' in WHERE clause, found " + current());
            }
            name = advance().text;
        }
        return name;
    }

    private List<Object> parseValueList() {
        if (current().type != SQLTokenizer.TokenType.LPAREN) {
            throw new IllegalArgumentException("Expected '(' after IN in WHERE clause.");
        }
        advance();
        List<Object> values = new ArrayList<>();
        if (current().type != SQLTokenizer.TokenType.RPAREN) {
            values.add(parseValue());
            while (current().type == SQLTokenizer.TokenType.COMMA) {
                advance();
                values.add(parseValue());
            }
        }
        if (current().type != SQLTokenizer.TokenType.RPAREN) {
            throw new IllegalArgumentException("Expected ')' to close IN list in WHERE clause.");
        }
        advance();
        return values;
    }

    private String parseStringLiteral(String context) {
        if (current().type != SQLTokenizer.TokenType.STRING) {
            throw new IllegalArgumentException("Expected a string literal after " + context + " in WHERE clause, found " + current());
        }
        return advance().text;
    }

    private Object parseValue() {
        SQLTokenizer.Token t = advance();
        switch (t.type) {
            case STRING:
                return t.text;
            case NUMBER:
                if (t.text.contains(".")) return Double.parseDouble(t.text);
                try {
                    return Integer.parseInt(t.text);
                } catch (NumberFormatException e) {
                    return Long.parseLong(t.text);
                }
            case IDENT:
                if ("true".equalsIgnoreCase(t.text) || "false".equalsIgnoreCase(t.text)) {
                    return Boolean.parseBoolean(t.text);
                }
                if ("null".equalsIgnoreCase(t.text)) {
                    return null;
                }
                return t.text;
            default:
                throw new IllegalArgumentException("Expected a value in WHERE clause, found " + t);
        }
    }
}
