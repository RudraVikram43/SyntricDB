package com.syntricdb.sql;

import com.syntricdb.engine.schema.ColumnDef;
import com.syntricdb.engine.schema.Tuple;
import java.util.*;

public class AST {

    public interface Statement {}

    public static class SetStatement implements Statement {
        private final String expression;
        public SetStatement(String expression) { this.expression = expression; }
        public String getExpression() { return expression; }
    }

    public static class NoOpStatement implements Statement {}

    public static class CreateTableStatement implements Statement {
        private final String tableName;
        private final List<ColumnDef> columns = new ArrayList<>();

        public CreateTableStatement(String tableName) {
            this.tableName = tableName.toLowerCase();
        }

        public CreateTableStatement addColumn(ColumnDef col) {
            columns.add(col);
            return this;
        }

        public String getTableName() { return tableName; }
        public List<ColumnDef> getColumns() { return columns; }
    }

    public static class InsertStatement implements Statement {
        private final String tableName;
        private final Tuple tuple;

        public InsertStatement(String tableName, Tuple tuple) {
            this.tableName = tableName.toLowerCase();
            this.tuple = tuple;
        }

        public String getTableName() { return tableName; }
        public Tuple getTuple() { return tuple; }
    }

    public static class SelectStatement implements Statement {
        private final String tableName;
        private final List<SelectItem> selectItems = new ArrayList<>();
        private final List<Condition> whereConditions = new ArrayList<>();
        private WhereExpr whereExpression;
        private VectorSearchCondition vectorSearchCondition;
        private FullTextCondition fullTextCondition;
        private String orderByColumn;
        private boolean orderByDesc = false;
        private int limit = -1;

        public SelectStatement(String tableName) {
            this.tableName = tableName != null ? tableName.toLowerCase() : null;
        }

        public String getTableName() { return tableName; }
        public List<SelectItem> getSelectItems() { return selectItems; }
        /** Flat top-level AND'd equality/comparison conditions, for index-selection hints only. */
        public List<Condition> getWhereConditions() { return whereConditions; }
        /** The full WHERE expression (AND/OR/NOT/LIKE/IN/BETWEEN/IS NULL/parens) used for actual row filtering. */
        public WhereExpr getWhereExpression() { return whereExpression; }
        public void setWhereExpression(WhereExpr whereExpression) { this.whereExpression = whereExpression; }
        public VectorSearchCondition getVectorSearchCondition() { return vectorSearchCondition; }
        public void setVectorSearchCondition(VectorSearchCondition v) { this.vectorSearchCondition = v; }
        public FullTextCondition getFullTextCondition() { return fullTextCondition; }
        public void setFullTextCondition(FullTextCondition f) { this.fullTextCondition = f; }
        public String getOrderByColumn() { return orderByColumn; }
        public void setOrderByColumn(String col) { this.orderByColumn = col; }
        public boolean isOrderByDesc() { return orderByDesc; }
        public void setOrderByDesc(boolean desc) { this.orderByDesc = desc; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
    }

    public static class SelectItem {
        private final String columnName;
        private final String alias;
        private final String aiFunction; // e.g., AI_SUMMARIZE, AI_CLASSIFY
        private final String[] aiArgs;

        public SelectItem(String columnName) {
            this(columnName, null, null, null);
        }

        public SelectItem(String columnName, String alias, String aiFunction, String[] aiArgs) {
            this.columnName = columnName;
            this.alias = alias != null ? alias : (aiFunction != null ? aiFunction.toLowerCase() + "_" + columnName : columnName);
            this.aiFunction = aiFunction;
            this.aiArgs = aiArgs;
        }

        public String getColumnName() { return columnName; }
        public String getAlias() { return alias; }
        public String getAiFunction() { return aiFunction; }
        public String[] getAiArgs() { return aiArgs; }
    }

    public static class Condition {
        private final String column;
        private final String operator; // =, !=, >, <, >=, <=
        private final Object value;

        public Condition(String column, String operator, Object value) {
            this.column = column.toLowerCase();
            this.operator = operator;
            this.value = value;
        }

        public String getColumn() { return column; }
        public String getOperator() { return operator; }
        public Object getValue() { return value; }
    }

    /**
     * A general boolean WHERE-clause expression tree: AND/OR/NOT of comparisons, LIKE,
     * IN, BETWEEN, and IS [NOT] NULL, with parentheses for grouping. Evaluated uniformly
     * against a row by {@link WhereEvaluator}, regardless of which database, table, or
     * access strategy (full scan, primary key, HNSW, inverted index) produced it.
     */
    public interface WhereExpr {}

    public static class ComparisonExpr implements WhereExpr {
        private final String column;
        private final String operator; // =, !=, >, <, >=, <=
        private final Object value;

        public ComparisonExpr(String column, String operator, Object value) {
            this.column = column.toLowerCase();
            this.operator = operator;
            this.value = value;
        }

        public String getColumn() { return column; }
        public String getOperator() { return operator; }
        public Object getValue() { return value; }
    }

    public static class LikeExpr implements WhereExpr {
        private final String column;
        private final String pattern;
        private final boolean negated;

        public LikeExpr(String column, String pattern, boolean negated) {
            this.column = column.toLowerCase();
            this.pattern = pattern;
            this.negated = negated;
        }

        public String getColumn() { return column; }
        public String getPattern() { return pattern; }
        public boolean isNegated() { return negated; }
    }

    public static class InExpr implements WhereExpr {
        private final String column;
        private final List<Object> values;
        private final boolean negated;

        public InExpr(String column, List<Object> values, boolean negated) {
            this.column = column.toLowerCase();
            this.values = values;
            this.negated = negated;
        }

        public String getColumn() { return column; }
        public List<Object> getValues() { return values; }
        public boolean isNegated() { return negated; }
    }

    public static class BetweenExpr implements WhereExpr {
        private final String column;
        private final Object low;
        private final Object high;
        private final boolean negated;

        public BetweenExpr(String column, Object low, Object high, boolean negated) {
            this.column = column.toLowerCase();
            this.low = low;
            this.high = high;
            this.negated = negated;
        }

        public String getColumn() { return column; }
        public Object getLow() { return low; }
        public Object getHigh() { return high; }
        public boolean isNegated() { return negated; }
    }

    public static class IsNullExpr implements WhereExpr {
        private final String column;
        private final boolean negated; // true = IS NOT NULL

        public IsNullExpr(String column, boolean negated) {
            this.column = column.toLowerCase();
            this.negated = negated;
        }

        public String getColumn() { return column; }
        public boolean isNegated() { return negated; }
    }

    public static class AndExpr implements WhereExpr {
        private final List<WhereExpr> operands;
        public AndExpr(List<WhereExpr> operands) { this.operands = operands; }
        public List<WhereExpr> getOperands() { return operands; }
    }

    public static class OrExpr implements WhereExpr {
        private final List<WhereExpr> operands;
        public OrExpr(List<WhereExpr> operands) { this.operands = operands; }
        public List<WhereExpr> getOperands() { return operands; }
    }

    public static class NotExpr implements WhereExpr {
        private final WhereExpr operand;
        public NotExpr(WhereExpr operand) { this.operand = operand; }
        public WhereExpr getOperand() { return operand; }
    }

    public static class VectorSearchCondition {
        private final String vectorColumn;
        private final String queryText;
        private final float[] targetVector;
        private final int k;
        private final double maxDistance;

        public VectorSearchCondition(String vectorColumn, String queryText, int k) {
            this(vectorColumn, queryText, null, k, 1.0);
        }

        public VectorSearchCondition(String vectorColumn, String queryText, float[] targetVector, int k, double maxDistance) {
            this.vectorColumn = vectorColumn.toLowerCase();
            this.queryText = queryText;
            this.targetVector = targetVector;
            this.k = k;
            this.maxDistance = maxDistance;
        }

        public String getVectorColumn() { return vectorColumn; }
        public String getQueryText() { return queryText; }
        public float[] getTargetVector() { return targetVector; }
        public int getK() { return k; }
        public double getMaxDistance() { return maxDistance; }
    }

    public static class FullTextCondition {
        private final String column;
        private final String queryText;

        public FullTextCondition(String column, String queryText) {
            this.column = column != null ? column.toLowerCase() : null;
            this.queryText = queryText;
        }

        public String getColumn() { return column; }
        public String getQueryText() { return queryText; }
    }

    public static class StreamPublishStatement implements Statement {
        private final String topic;
        private final Map<String, Object> payload;

        public StreamPublishStatement(String topic, Map<String, Object> payload) {
            this.topic = topic.toLowerCase();
            this.payload = payload;
        }

        public String getTopic() { return topic; }
        public Map<String, Object> getPayload() { return payload; }
    }

    public static class CreateDatabaseStatement implements Statement {
        private final String dbName;

        public CreateDatabaseStatement(String dbName) {
            this.dbName = dbName.toLowerCase();
        }

        public String getDbName() { return dbName; }
    }

    public static class DropDatabaseStatement implements Statement {
        private final String dbName;

        public DropDatabaseStatement(String dbName) {
            this.dbName = dbName.toLowerCase();
        }

        public String getDbName() { return dbName; }
    }

    public static class UseDatabaseStatement implements Statement {
        private final String dbName;

        public UseDatabaseStatement(String dbName) {
            this.dbName = dbName.toLowerCase();
        }

        public String getDbName() { return dbName; }
    }

    public static class ShowDatabasesStatement implements Statement {}

    public static class ShowTablesStatement implements Statement {
        private final String dbName;

        public ShowTablesStatement(String dbName) {
            this.dbName = dbName != null ? dbName.toLowerCase() : null;
        }

        public String getDbName() { return dbName; }
    }

    public static class UpdateStatement implements Statement {
        private final String tableName;
        private final Map<String, Object> setAssignments = new LinkedHashMap<>();
        private WhereExpr whereExpression;

        public UpdateStatement(String tableName) {
            this.tableName = tableName.toLowerCase();
        }

        public UpdateStatement addAssignment(String column, Object value) {
            setAssignments.put(column, value);
            return this;
        }

        public String getTableName() { return tableName; }
        public Map<String, Object> getSetAssignments() { return setAssignments; }
        public WhereExpr getWhereExpression() { return whereExpression; }
        public void setWhereExpression(WhereExpr whereExpression) { this.whereExpression = whereExpression; }
    }

    public static class DeleteStatement implements Statement {
        private final String tableName;
        private WhereExpr whereExpression;

        public DeleteStatement(String tableName) {
            this.tableName = tableName.toLowerCase();
        }

        public String getTableName() { return tableName; }
        public WhereExpr getWhereExpression() { return whereExpression; }
        public void setWhereExpression(WhereExpr whereExpression) { this.whereExpression = whereExpression; }
    }
}

