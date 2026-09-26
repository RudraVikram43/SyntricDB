package com.syntricdb;

import com.syntricdb.ai.AIEngine;
import com.syntricdb.engine.StorageEngine;
import com.syntricdb.engine.cache.MemoryCacheEngine;
import com.syntricdb.net.PGWireServerHandler;
import com.syntricdb.net.RESPProtocolHandler;
import com.syntricdb.sql.QueryExecutor;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PGWireAndRESPTest {

    @TempDir
    Path tempDir;

    private StorageEngine storageEngine;
    private AIEngine aiEngine;
    private QueryExecutor queryExecutor;
    private MemoryCacheEngine cacheEngine;

    @BeforeEach
    public void setUp() throws Exception {
        storageEngine = new StorageEngine(tempDir);
        aiEngine = new AIEngine(128);
        queryExecutor = new QueryExecutor(storageEngine, aiEngine);
        cacheEngine = storageEngine.getCacheEngine();
    }

    @Test
    public void testRESPHandlerSetAndGet() {
        EmbeddedChannel channel = new EmbeddedChannel(new RESPProtocolHandler(cacheEngine));

        // SET key value
        io.netty.buffer.ByteBuf setBuf = io.netty.buffer.Unpooled.copiedBuffer("SET user_101 Upendra\r\n", java.nio.charset.StandardCharsets.UTF_8);
        channel.writeInbound(setBuf);

        io.netty.buffer.ByteBuf resp1 = channel.readOutbound();
        assertNotNull(resp1);
        String resp1Str = resp1.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(resp1Str.contains("OK"));

        // GET key
        io.netty.buffer.ByteBuf getBuf = io.netty.buffer.Unpooled.copiedBuffer("GET user_101\r\n", java.nio.charset.StandardCharsets.UTF_8);
        channel.writeInbound(getBuf);

        io.netty.buffer.ByteBuf resp2 = channel.readOutbound();
        assertNotNull(resp2);
        String resp2Str = resp2.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(resp2Str.contains("Upendra"));
    }

    @Test
    public void testPGWireHandlerChannelInit() {
        EmbeddedChannel channel = new EmbeddedChannel(new PGWireServerHandler(queryExecutor));
        assertTrue(channel.isOpen());
    }

    @Test
    public void testPGWireExtendedProtocolDoesNotDoubleExecuteInsert() throws Exception {
        queryExecutor.execute("CREATE TABLE products (id VARCHAR PRIMARY KEY, title VARCHAR, price DOUBLE)");

        EmbeddedChannel channel = new EmbeddedChannel(new PGWireServerHandler(queryExecutor));
        io.netty.buffer.ByteBuf startupBuf = io.netty.buffer.Unpooled.buffer();
        startupBuf.writeInt(19);
        startupBuf.writeInt(196608);
        startupBuf.writeBytes("user\0admin\0database\0default\0\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        channel.writeInbound(startupBuf);
        drainOutbound(channel);

        String insertSql = "INSERT INTO products VALUES ('pgw1', 'Widget', 1.0)";
        channel.writeInbound(pgMessage('P', buf -> {
            buf.writeByte(0);
            buf.writeBytes(insertSql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buf.writeByte(0);
            buf.writeShort(0);
        }));
        channel.writeInbound(pgMessage('B', buf -> {
            buf.writeByte(0);
            buf.writeByte(0);
            buf.writeShort(0);
            buf.writeShort(0);
        }));
        channel.writeInbound(pgMessage('D', buf -> {
            buf.writeByte('S');
            buf.writeByte(0);
        }));
        channel.writeInbound(pgMessage('E', buf -> {
            buf.writeByte(0);
            buf.writeInt(0);
        }));
        channel.writeInbound(pgMessage('S', buf -> {}));
        drainOutbound(channel);

        assertEquals(1, storageEngine.scanAll("default", "products").size(),
                "Describe + Execute over the extended query protocol must apply an INSERT exactly once");
    }

    private void drainOutbound(EmbeddedChannel channel) {
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                ((io.netty.buffer.ByteBuf) msg).release();
            }
        }
    }

    private io.netty.buffer.ByteBuf pgMessage(char type, java.util.function.Consumer<io.netty.buffer.ByteBuf> payloadWriter) {
        io.netty.buffer.ByteBuf payload = io.netty.buffer.Unpooled.buffer();
        payloadWriter.accept(payload);
        io.netty.buffer.ByteBuf full = io.netty.buffer.Unpooled.buffer();
        full.writeByte(type);
        full.writeInt(4 + payload.readableBytes());
        full.writeBytes(payload);
        payload.release();
        return full;
    }
}
