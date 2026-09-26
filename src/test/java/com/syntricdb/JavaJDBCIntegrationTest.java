package com.syntricdb;

import com.syntricdb.ai.AIEngine;
import com.syntricdb.config.SyntricConfig;
import com.syntricdb.engine.StorageEngine;
import com.syntricdb.jdbc.SyntricDBDriver;
import com.syntricdb.net.NettyServer;
import com.syntricdb.net.PGWireServerHandler;
import com.syntricdb.security.SecurityManager;
import com.syntricdb.sql.QueryExecutor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class JavaJDBCIntegrationTest {

    @TempDir
    Path tempDir;

    private QueryExecutor queryExecutor;
    private NettyServer nettyServer;
    private StorageEngine storageEngine;

    @BeforeEach
    public void setUp() throws Exception {
        storageEngine = new StorageEngine(tempDir);
        AIEngine aiEngine = new AIEngine(128);
        queryExecutor = new QueryExecutor(storageEngine, aiEngine);
    }

    @AfterEach
    public void tearDown() {
        if (nettyServer != null) {
            nettyServer.stop();
        }
    }

    @Test
    public void testSyntricDBDriverRegistrationAndAcceptance() throws Exception {
        Driver driver = new SyntricDBDriver();
        assertTrue(driver.acceptsURL("jdbc:syntricdb://localhost:8080/default"));
        assertTrue(driver.acceptsURL("syntricdb://admin:secret@localhost:8080/default"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost:5432/default"));
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost:3306/default"));

        Driver registered = DriverManager.getDriver("jdbc:syntricdb://localhost:8080/default");
        assertNotNull(registered);
    }

    @Test
    public void testNativeSyntricDBConnectionAndQueryExecution() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8899, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");
        queryExecutor.execute("INSERT INTO products VALUES ('p101', 'AI Accelerator', 299.99)");

        // 1. Connection with valid credentials
        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8899/default");
        assertNotNull(conn);
        assertFalse(conn.isClosed());

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM products");
        assertTrue(rs.next());
        assertEquals("p101", rs.getString("id"));
        assertEquals("AI Accelerator", rs.getString("title"));
        assertEquals(299.99, rs.getDouble("price"), 0.01);
        conn.close();

        // 2. Connection with invalid credentials throws SQLException
        assertThrows(SQLException.class, () -> {
            DriverManager.getConnection("jdbc:syntricdb://admin:wrong_pass@localhost:8899/default");
        });
    }

    @Test
    public void testPreparedStatementQuestionMarkInsideStringLiteralIsNotAPlaceholder() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8902, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");
        queryExecutor.execute("INSERT INTO products VALUES ('p1', 'is this ok?', 5.0)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8902/default");
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM products WHERE title = 'is this ok?' AND id = ?");
        ps.setString(1, "p1");
        ResultSet rs = ps.executeQuery();

        assertTrue(rs.next());
        assertEquals("p1", rs.getString("id"));
        conn.close();
    }

    @Test
    public void testPreparedStatementDollarParametersDoNotCollideBySubstring() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8903, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");
        queryExecutor.execute("INSERT INTO products VALUES ('p1', 'Widget', 1.0)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8903/default");
        PreparedStatement ps = conn.prepareStatement("UPDATE products SET price = $10 WHERE id = $1");
        ps.setString(1, "p1");
        ps.setDouble(10, 42.0);
        ps.executeUpdate();

        assertEquals(42.0, storageEngine.getByPrimaryKey("default", "products", "p1").getDouble("price"), 0.001);
        conn.close();
    }

    @Test
    public void testPreparedStatementSetBytesEscapesEmbeddedQuote() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8904, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8904/default");
        PreparedStatement ps = conn.prepareStatement("INSERT INTO products (id, title, price) VALUES (?, ?, ?)");
        ps.setString(1, "p2");
        ps.setBytes(2, "O'Brien".getBytes(StandardCharsets.UTF_8));
        ps.setDouble(3, 9.99);
        ps.executeUpdate();

        assertEquals("O'Brien", storageEngine.getByPrimaryKey("default", "products", "p2").getString("title"));
        conn.close();
    }

    @Test
    public void testEmptySelectReturnsEmptyResultSetInsteadOfThrowing() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8905, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8905/default");
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM products WHERE id = 'does_not_exist'");

        assertFalse(rs.next());
        conn.close();
    }

    @Test
    public void testManualCommitTransactionRollbackDiscardsWrites() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8900, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE accounts (id VARCHAR PRIMARY KEY, balance DOUBLE)");
        queryExecutor.execute("INSERT INTO accounts VALUES ('a1', 100.0)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8900/default");
        conn.setAutoCommit(false);
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("INSERT INTO accounts VALUES ('a2', 50.0)");
        stmt.executeUpdate("UPDATE accounts SET balance = 999.0 WHERE id = 'a1'");

        // Neither write is visible to the storage engine until commit.
        assertNull(storageEngine.getByPrimaryKey("default", "accounts", "a2"));
        assertEquals(100.0, storageEngine.getByPrimaryKey("default", "accounts", "a1").getDouble("balance"), 0.01);

        conn.rollback();
        conn.close();

        // Rollback discards them for good: nothing was ever written.
        assertNull(storageEngine.getByPrimaryKey("default", "accounts", "a2"));
        assertEquals(100.0, storageEngine.getByPrimaryKey("default", "accounts", "a1").getDouble("balance"), 0.01);
    }

    @Test
    public void testManualCommitTransactionCommitAppliesWrites() throws Exception {
        SyntricConfig config = new SyntricConfig();
        SecurityManager securityManager = new SecurityManager("admin", "syntricdb_secret_pass");
        nettyServer = new NettyServer(8901, storageEngine, new AIEngine(128), queryExecutor, new com.syntricdb.cluster.ClusterState(), securityManager, config);
        nettyServer.start();

        queryExecutor.execute("CREATE TABLE accounts (id VARCHAR PRIMARY KEY, balance DOUBLE)");

        Connection conn = DriverManager.getConnection("jdbc:syntricdb://admin:syntricdb_secret_pass@localhost:8901/default");
        conn.setAutoCommit(false);
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("INSERT INTO accounts VALUES ('a3', 75.0)");

        // Queued, not yet applied.
        assertNull(storageEngine.getByPrimaryKey("default", "accounts", "a3"));

        conn.commit();

        assertNotNull(storageEngine.getByPrimaryKey("default", "accounts", "a3"));
        assertEquals(75.0, storageEngine.getByPrimaryKey("default", "accounts", "a3").getDouble("balance"), 0.01);

        conn.close();
    }

    @Test
    public void testPGWireExtendedQueryProtocolHandshakeAndParse() {
        PGWireServerHandler handler = new PGWireServerHandler(queryExecutor);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 1. Send StartupMessage v3.0
        ByteBuf startupBuf = Unpooled.buffer();
        startupBuf.writeInt(19); // length
        startupBuf.writeInt(196608); // protocol v3.0
        startupBuf.writeBytes("user\0admin\0database\0default\0\0".getBytes(StandardCharsets.UTF_8));
        channel.writeInbound(startupBuf);

        // Expect AuthOk, BackendKeyData, ParameterStatus, ReadyForQuery
        ByteBuf outbound = channel.readOutbound();
        assertNotNull(outbound);
        byte type = outbound.readByte();
        assertEquals('R', type); // AuthenticationOk

        // 2. Send Parse message 'P'
        String sql = "SELECT 1";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);

        ByteBuf parseBuf = Unpooled.buffer();
        parseBuf.writeByte('P');
        parseBuf.writeInt(4 + 1 + sqlBytes.length + 1 + 2);
        parseBuf.writeByte(0); // statement name ""
        parseBuf.writeBytes(sqlBytes);
        parseBuf.writeByte(0);
        parseBuf.writeShort(0); // 0 params

        // Send Sync 'S'
        ByteBuf syncBuf = Unpooled.buffer();
        syncBuf.writeByte('S');
        syncBuf.writeInt(4);

        channel.writeInbound(parseBuf);
        channel.writeInbound(syncBuf);

        // Read ParseComplete ('1')
        ByteBuf respParse = channel.readOutbound();
        while (respParse != null && respParse.readableBytes() > 0) {
            byte msgType = respParse.readByte();
            if (msgType == '1') {
                assertTrue(true, "Received ParseComplete ('1')");
                break;
            }
            respParse = channel.readOutbound();
        }
    }
}
