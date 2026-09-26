package com.syntricdb.sql;

import com.syntricdb.engine.schema.Tuple;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Single evaluator for WHERE-clause predicates, used by every code path that filters rows
 * (SELECT's pushdown filter, UPDATE, DELETE) so behavior is identical regardless of which
 * database/table/access strategy produced the candidate row. Previously this logic was
 * duplicated (and had drifted, including a bug in "!=") across three separate places.
 */
public class WhereEvaluator {

    private WhereEvaluator() {}

    public static boolean matches(Tuple tuple, AST.WhereExpr expr) {
        if (expr == null) return true;
        return eval(tuple, expr);
    }

    /** For the flat, implicitly-AND'ed condition list used as an index-selection hint. */
    public static boolean matches(Tuple tuple, List<AST.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return true;
        for (AST.Condition c : conditions) {
            if (!evalComparison(tuple.get(c.getColumn()), c.getOperator(), c.getValue())) return false;
        }
        return true;
    }

    private static boolean eval(Tuple tuple, AST.WhereExpr expr) {
        if (expr instanceof AST.AndExpr and) {
            for (AST.WhereExpr operand : and.getOperands()) {
                if (!eval(tuple, operand)) return false;
            }
            return true;
        }
        if (expr instanceof AST.OrExpr or) {
            for (AST.WhereExpr operand : or.getOperands()) {
                if (eval(tuple, operand)) return true;
            }
            return false;
        }
        if (expr instanceof AST.NotExpr not) {
            return !eval(tuple, not.getOperand());
        }
        if (expr instanceof AST.ComparisonExpr cmp) {
            return evalComparison(tuple.get(cmp.getColumn()), cmp.getOperator(), cmp.getValue());
        }
        if (expr instanceof AST.LikeExpr like) {
            Object val = tuple.get(like.getColumn());
            boolean matched = val != null && likeToPattern(like.getPattern()).matcher(val.toString()).matches();
            return like.isNegated() != matched;
        }
        if (expr instanceof AST.InExpr in) {
            Object val = tuple.get(in.getColumn());
            boolean matched = val != null && in.getValues().stream()
                    .anyMatch(v -> v != null && val.toString().equalsIgnoreCase(v.toString()));
            return in.isNegated() != matched;
        }
        if (expr instanceof AST.BetweenExpr between) {
            Object val = tuple.get(between.getColumn());
            boolean matched = val != null
                    && compare(val, between.getLow()) >= 0
                    && compare(val, between.getHigh()) <= 0;
            return between.isNegated() != matched;
        }
        if (expr instanceof AST.IsNullExpr isNull) {
            boolean valueIsNull = tuple.get(isNull.getColumn()) == null;
            return isNull.isNegated() != valueIsNull;
        }
        throw new IllegalStateException("Unknown WHERE expression node: " + expr.getClass());
    }

    private static boolean evalComparison(Object actualVal, String operator, Object targetVal) {
        if (actualVal == null) return false;
        switch (operator) {
            case "=": return actualVal.toString().equalsIgnoreCase(String.valueOf(targetVal));
            case "!=": return !actualVal.toString().equalsIgnoreCase(String.valueOf(targetVal));
            case ">": return compare(actualVal, targetVal) > 0;
            case "<": return compare(actualVal, targetVal) < 0;
            case ">=": return compare(actualVal, targetVal) >= 0;
            case "<=": return compare(actualVal, targetVal) <= 0;
            default: return false;
        }
    }

    /** Numeric comparison when both sides parse as numbers, otherwise case-insensitive lexicographic. */
    private static int compare(Object a, Object b) {
        Double da = tryParseDouble(a);
        Double db = tryParseDouble(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private static Double tryParseDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o == null) return null;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Pattern likeToPattern(String sqlPattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : sqlPattern.toCharArray()) {
            switch (c) {
                case '%': regex.append(".*"); break;
                case '_': regex.append('.'); break;
                default: regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
