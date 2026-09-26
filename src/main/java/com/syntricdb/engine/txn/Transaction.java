package com.syntricdb.engine.txn;

import com.syntricdb.engine.schema.Tuple;
import com.syntricdb.sql.AST;

import java.util.*;

public class Transaction {
    public enum TxnState {
        ACTIVE,
        COMMITTED,
        ABORTED
    }

    public enum OpType {
        INSERT, UPDATE, DELETE
    }

    /**
     * A single DML statement issued under this transaction, deferred until commit.
     * Nothing here is applied to the storage engine until {@link TransactionManager}
     * replays the list at commit time.
     */
    public static class PendingWrite {
        public final OpType type;
        public final String database;
        public final String table;
        public final Tuple tuple;
        public final Map<String, Object> setAssignments;
        public final AST.WhereExpr whereExpr;

        public static PendingWrite forInsert(String database, String table, Tuple tuple) {
            return new PendingWrite(OpType.INSERT, database, table, tuple, null, null);
        }

        public static PendingWrite forUpdate(String database, String table, Map<String, Object> setAssignments, AST.WhereExpr whereExpr) {
            return new PendingWrite(OpType.UPDATE, database, table, null, setAssignments, whereExpr);
        }

        public static PendingWrite forDelete(String database, String table, AST.WhereExpr whereExpr) {
            return new PendingWrite(OpType.DELETE, database, table, null, null, whereExpr);
        }

        private PendingWrite(OpType type, String database, String table, Tuple tuple, Map<String, Object> setAssignments, AST.WhereExpr whereExpr) {
            this.type = type;
            this.database = database;
            this.table = table;
            this.tuple = tuple;
            this.setAssignments = setAssignments;
            this.whereExpr = whereExpr;
        }
    }

    private final long txnId;
    private final long readTimestamp;
    private volatile TxnState state;
    private final Set<String> writeKeys = new HashSet<>();
    private final Map<String, Object> uncommittedModifications = new HashMap<>();
    private final List<PendingWrite> pendingWrites = Collections.synchronizedList(new ArrayList<>());

    public Transaction(long txnId, long readTimestamp) {
        this.txnId = txnId;
        this.readTimestamp = readTimestamp;
        this.state = TxnState.ACTIVE;
    }

    public void recordWrite(String key, Object value) {
        writeKeys.add(key);
        uncommittedModifications.put(key, value);
    }

    public void addPendingWrite(PendingWrite write) {
        pendingWrites.add(write);
    }

    public long getTxnId() { return txnId; }
    public long getReadTimestamp() { return readTimestamp; }
    public TxnState getState() { return state; }
    public void setState(TxnState state) { this.state = state; }
    public Set<String> getWriteKeys() { return writeKeys; }
    public Map<String, Object> getUncommittedModifications() { return uncommittedModifications; }
    public List<PendingWrite> getPendingWrites() { return pendingWrites; }
}
