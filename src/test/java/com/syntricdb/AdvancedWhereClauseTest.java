package com.syntricdb;

import com.syntricdb.ai.AIEngine;
import com.syntricdb.engine.StorageEngine;
import com.syntricdb.sql.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the WHERE-clause features added on top of the original AND-only, six-operator
 * grammar: OR, NOT, parentheses, LIKE, IN, BETWEEN, and IS [NOT] NULL, applied uniformly
 * across SELECT/UPDATE/DELETE and across databases (the evaluation itself no longer cares
 * which database or table produced the candidate rows).
 */
public class AdvancedWhereClauseTest {

    @TempDir
    Path tempDir;

    private QueryExecutor executor;

    @BeforeEach
    public void setup() throws Exception {
        StorageEngine engine = new StorageEngine(tempDir);
        AIEngine ai = new AIEngine(128);
        executor = new QueryExecutor(engine, ai);

        executor.execute("CREATE TABLE users (id VARCHAR PRIMARY KEY, name VARCHAR, city VARCHAR, age INT, bio VARCHAR, embedding FLOAT_VECTOR(128))");
        executor.execute("INSERT INTO users VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Java Engineer', AI_EMBED('Java Engineer'))");
        executor.execute("INSERT INTO users VALUES ('u2', 'Bob', 'London', 40, 'Quantum Physicist', AI_EMBED('Quantum Physicist'))");
        executor.execute("INSERT INTO users VALUES ('u3', 'Carol', 'Hyderabad', 25, 'Data Scientist', AI_EMBED('Data Scientist'))");
        executor.execute("INSERT INTO users VALUES ('u4', 'Dave', 'Berlin', 50, null, AI_EMBED('Manager'))");
    }

    private List<String> ids(QueryExecutor.QueryResult result) {
        return result.getRows().stream().map(r -> (String) r.get("id")).sorted().collect(Collectors.toList());
    }

    @Test
    public void testOrCondition() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE city = 'London' OR city = 'Berlin'");
        assertEquals(List.of("u2", "u4"), ids(res));
    }

    @Test
    public void testAndBindsTighterThanOr() throws Exception {
        // city='Hyderabad' AND age<30  OR  city='London'  ->  (u1&&30<30=false, u3&&25<30=true) or u2
        QueryExecutor.QueryResult res = executor.execute(
                "SELECT id FROM users WHERE city = 'Hyderabad' AND age < 30 OR city = 'London'");
        assertEquals(List.of("u2", "u3"), ids(res));
    }

    @Test
    public void testParenthesesOverridePrecedence() throws Exception {
        // city='Hyderabad' AND (age < 30 OR city='London') -> only u3 (Hyderabad + age<30)
        QueryExecutor.QueryResult res = executor.execute(
                "SELECT id FROM users WHERE city = 'Hyderabad' AND (age < 30 OR city = 'London')");
        assertEquals(List.of("u3"), ids(res));
    }

    @Test
    public void testNotCondition() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE NOT city = 'Hyderabad'");
        assertEquals(List.of("u2", "u4"), ids(res));
    }

    @Test
    public void testLikeWildcards() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE name LIKE 'A%'");
        assertEquals(List.of("u1"), ids(res));

        QueryExecutor.QueryResult res2 = executor.execute("SELECT id FROM users WHERE name LIKE '_ob'");
        assertEquals(List.of("u2"), ids(res2));
    }

    @Test
    public void testNotLike() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE name NOT LIKE 'A%'");
        assertEquals(List.of("u2", "u3", "u4"), ids(res));
    }

    @Test
    public void testInList() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE city IN ('London', 'Berlin')");
        assertEquals(List.of("u2", "u4"), ids(res));
    }

    @Test
    public void testNotInList() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE city NOT IN ('London', 'Berlin')");
        assertEquals(List.of("u1", "u3"), ids(res));
    }

    @Test
    public void testBetween() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE age BETWEEN 25 AND 30");
        assertEquals(List.of("u1", "u3"), ids(res));
    }

    @Test
    public void testNotBetween() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM users WHERE age NOT BETWEEN 25 AND 30");
        assertEquals(List.of("u2", "u4"), ids(res));
    }

    @Test
    public void testIsNullAndIsNotNull() throws Exception {
        QueryExecutor.QueryResult nullRes = executor.execute("SELECT id FROM users WHERE bio IS NULL");
        assertEquals(List.of("u4"), ids(nullRes));

        QueryExecutor.QueryResult notNullRes = executor.execute("SELECT id FROM users WHERE bio IS NOT NULL");
        assertEquals(List.of("u1", "u2", "u3"), ids(notNullRes));
    }

    @Test
    public void testUpdateWithOrCondition() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("UPDATE users SET age = 99 WHERE city = 'London' OR city = 'Berlin'");
        assertEquals(2, res.getAffectedRows());

        QueryExecutor.QueryResult check = executor.execute("SELECT id FROM users WHERE age = 99");
        assertEquals(List.of("u2", "u4"), ids(check));
    }

    @Test
    public void testDeleteWithInCondition() throws Exception {
        QueryExecutor.QueryResult res = executor.execute("DELETE FROM users WHERE city IN ('London', 'Berlin')");
        assertEquals(2, res.getAffectedRows());

        QueryExecutor.QueryResult remaining = executor.execute("SELECT id FROM users");
        assertEquals(List.of("u1", "u3"), ids(remaining));
    }

    @Test
    public void testAdvancedOperatorsConsistentAcrossDatabases() throws Exception {
        // Same WHERE-clause features must behave identically in a non-default database.
        executor.execute("CREATE DATABASE analytics_test");
        executor.execute("CREATE TABLE analytics_test.users (id VARCHAR PRIMARY KEY, name VARCHAR, city VARCHAR, age INT, bio VARCHAR, embedding FLOAT_VECTOR(128))");
        executor.execute("INSERT INTO analytics_test.users VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Java Engineer', AI_EMBED('Java Engineer'))");
        executor.execute("INSERT INTO analytics_test.users VALUES ('u2', 'Bob', 'London', 40, 'Quantum Physicist', AI_EMBED('Quantum Physicist'))");

        QueryExecutor.QueryResult res = executor.execute("SELECT id FROM analytics_test.users WHERE city = 'London' OR age BETWEEN 20 AND 30");
        assertEquals(List.of("u1", "u2"), ids(res));
    }

    @Test
    public void testMalformedWhereClauseFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("SELECT id FROM users WHERE age BETWEEN"));
    }
}
