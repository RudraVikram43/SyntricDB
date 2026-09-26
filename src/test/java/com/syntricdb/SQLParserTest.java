package com.syntricdb;

import com.syntricdb.ai.AIEngine;
import com.syntricdb.sql.AST;
import com.syntricdb.sql.SQLParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SQLParserTest {

    private SQLParser parser;

    @BeforeEach
    public void setup() {
        AIEngine aiEngine = new AIEngine(128);
        parser = new SQLParser(aiEngine);
    }

    @Test
    public void testParseCreateTable() throws Exception {
        AST.Statement stmt = parser.parse("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");
        assertTrue(stmt instanceof AST.CreateTableStatement);

        AST.CreateTableStatement create = (AST.CreateTableStatement) stmt;
        assertEquals("products", create.getTableName());
        assertEquals(3, create.getColumns().size());
    }

    @Test
    public void testParseVectorSearchSelect() throws Exception {
        AST.Statement stmt = parser.parse("SELECT id, name FROM users WHERE embedding SIMILAR TO 'Java Systems' TOP 5");
        assertTrue(stmt instanceof AST.SelectStatement);

        AST.SelectStatement select = (AST.SelectStatement) stmt;
        assertEquals("users", select.getTableName());
        assertNotNull(select.getVectorSearchCondition());
        assertEquals("embedding", select.getVectorSearchCondition().getVectorColumn());
        assertEquals("Java Systems", select.getVectorSearchCondition().getQueryText());
        assertEquals(5, select.getVectorSearchCondition().getK());
    }

    @Test
    public void testParseFullTextMatchSelect() throws Exception {
        AST.Statement stmt = parser.parse("SELECT id, bio FROM users WHERE MATCH(bio, 'vector search')");
        assertTrue(stmt instanceof AST.SelectStatement);

        AST.SelectStatement select = (AST.SelectStatement) stmt;
        assertNotNull(select.getFullTextCondition());
        assertEquals("bio", select.getFullTextCondition().getColumn());
        assertEquals("vector search", select.getFullTextCondition().getQueryText());
    }

    @Test
    public void testParseSelectWithTableAlias() throws Exception {
        AST.Statement stmt = parser.parse("SELECT p1_0.id, p1_0.name FROM products p1_0 WHERE p1_0.id = 'x'");
        assertTrue(stmt instanceof AST.SelectStatement);

        AST.SelectStatement select = (AST.SelectStatement) stmt;
        assertEquals("products", select.getTableName());
        assertEquals("id", select.getSelectItems().get(0).getColumnName());
        assertEquals("name", select.getSelectItems().get(1).getColumnName());
        assertEquals("id", select.getWhereConditions().get(0).getColumn());
    }

    @Test
    public void testParseSelectWithAsAlias() throws Exception {
        AST.Statement stmt = parser.parse("SELECT * FROM products AS p");
        assertTrue(stmt instanceof AST.SelectStatement);
        assertEquals("products", ((AST.SelectStatement) stmt).getTableName());
    }

    @Test
    public void testParseSelectRejectsMalformedTrailingClause() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("SELECT * FROM users LIMT 5"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("SELECT * FROM users garbage tokens"));
    }

    @Test
    public void testParseSelectHandlesGreaterAndLessThanOrEqualOperators() throws Exception {
        AST.Statement stmt = parser.parse("SELECT * FROM users WHERE age >= 5");
        assertTrue(stmt instanceof AST.SelectStatement);
        AST.Condition cond = ((AST.SelectStatement) stmt).getWhereConditions().get(0);
        assertEquals("age", cond.getColumn());
        assertEquals(">=", cond.getOperator());
        assertEquals(5, cond.getValue());

        AST.Statement stmt2 = parser.parse("SELECT * FROM users WHERE age <= 5");
        AST.Condition cond2 = ((AST.SelectStatement) stmt2).getWhereConditions().get(0);
        assertEquals("<=", cond2.getOperator());
        assertEquals(5, cond2.getValue());
    }

    @Test
    public void testParseSelectPreservesDecimalLiteralInSelectList() throws Exception {
        AST.Statement stmt = parser.parse("SELECT price * 1.5 AS total FROM products");
        assertTrue(stmt instanceof AST.SelectStatement);
        assertEquals("price * 1.5 AS total", ((AST.SelectStatement) stmt).getSelectItems().get(0).getColumnName());
    }

    @Test
    public void testParseWhereOr() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE city = 'A' OR city = 'B'");
        assertTrue(stmt.getWhereExpression() instanceof AST.OrExpr);
        assertEquals(2, ((AST.OrExpr) stmt.getWhereExpression()).getOperands().size());
    }

    @Test
    public void testParseWhereAndBindsTighterThanOr() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE a = 1 AND b = 2 OR c = 3");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.OrExpr);
        AST.OrExpr or = (AST.OrExpr) expr;
        assertEquals(2, or.getOperands().size());
        assertTrue(or.getOperands().get(0) instanceof AST.AndExpr);
        assertTrue(or.getOperands().get(1) instanceof AST.ComparisonExpr);
    }

    @Test
    public void testParseWhereParenthesesOverridePrecedence() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE a = 1 AND (b = 2 OR c = 3)");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.AndExpr);
        AST.AndExpr and = (AST.AndExpr) expr;
        assertEquals(2, and.getOperands().size());
        assertTrue(and.getOperands().get(1) instanceof AST.OrExpr);
    }

    @Test
    public void testParseWhereNot() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE NOT city = 'A'");
        assertTrue(stmt.getWhereExpression() instanceof AST.NotExpr);
    }

    @Test
    public void testParseWhereLike() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE name LIKE 'A%'");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.LikeExpr);
        AST.LikeExpr like = (AST.LikeExpr) expr;
        assertEquals("name", like.getColumn());
        assertEquals("A%", like.getPattern());
        assertFalse(like.isNegated());
    }

    @Test
    public void testParseWhereNotLike() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE name NOT LIKE 'A%'");
        assertTrue(((AST.LikeExpr) stmt.getWhereExpression()).isNegated());
    }

    @Test
    public void testParseWhereIn() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE city IN ('A', 'B', 'C')");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.InExpr);
        AST.InExpr in = (AST.InExpr) expr;
        assertEquals("city", in.getColumn());
        assertEquals(3, in.getValues().size());
        assertFalse(in.isNegated());
    }

    @Test
    public void testParseWhereBetween() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE age BETWEEN 18 AND 65");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.BetweenExpr);
        AST.BetweenExpr between = (AST.BetweenExpr) expr;
        assertEquals("age", between.getColumn());
        assertEquals(18, between.getLow());
        assertEquals(65, between.getHigh());
    }

    @Test
    public void testParseWhereIsNull() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE bio IS NULL");
        AST.WhereExpr expr = stmt.getWhereExpression();
        assertTrue(expr instanceof AST.IsNullExpr);
        assertFalse(((AST.IsNullExpr) expr).isNegated());

        AST.SelectStatement stmt2 = (AST.SelectStatement) parser.parse("SELECT * FROM users WHERE bio IS NOT NULL");
        assertTrue(((AST.IsNullExpr) stmt2.getWhereExpression()).isNegated());
    }

    @Test
    public void testParseWhereVectorSearchStillCombinesWithAnd() throws Exception {
        AST.SelectStatement stmt = (AST.SelectStatement) parser.parse(
                "SELECT * FROM users WHERE city = 'A' AND embedding SIMILAR TO 'query text' TOP 3");
        assertNotNull(stmt.getVectorSearchCondition());
        assertTrue(stmt.getWhereExpression() instanceof AST.ComparisonExpr);
    }
}
