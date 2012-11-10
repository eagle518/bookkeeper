/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.bookie;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import junit.framework.TestCase;

import org.apache.bookkeeper.bookie.GarbageCollectorThread.EntryLogMetadata;
import org.apache.bookkeeper.bookie.GarbageCollectorThread.ExtractionScanner;
import org.apache.bookkeeper.bookie.BufferedReorderedWriteChannel;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntryLogTest extends TestCase {
    static Logger LOG = LoggerFactory.getLogger(EntryLogTest.class);

    @Before
    public void setUp() throws Exception {
    }

    private boolean verifyData(byte[] src, byte[] dst, int pos, int cap) {
        for (int i = pos; i < pos + cap; i++) {
            if (src[i] != dst[i - pos]) {
                return false;
            }
        }
        return true;
    }

    private ByteBuffer getBufferOfSize(int size) {
        byte[] ret = new byte[size];
        for (int i = 0; i < size; i++) {
            ret[i] = (byte)(i % Byte.MAX_VALUE);
        }
        return ByteBuffer.wrap(ret);
    }

    private BufferedReorderedWriteChannel getReorderedWriteChannel(int writeCap,
                                                                   int readCap,
                                                                   int chunkSize) throws IOException {
        File tmpFile = File.createTempFile("BufferedReorderedTest", ".tmp");
        RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
        return new BufferedReorderedWriteChannel(raf.getChannel(), writeCap, readCap, chunkSize);
    }

    @Test
    public void testBufferedReadChannel() throws Exception {
        File tmpFile = File.createTempFile("bufferedReadTest", ".tmp");
        RandomAccessFile raf = null;
        BufferedChannel bc = new BufferedChannel((raf = new RandomAccessFile(tmpFile, "rw")).getChannel(), 64);
        final int cap = 2048;
        byte[] src = new byte[cap];
        byte [] dst = new byte[cap];
        ByteBuffer dstBuff = null;
        // Populate the file
        for (int i = 0; i < cap; i++) {
            src[i] = (byte)(i % Byte.MAX_VALUE);
        }
        bc.write(ByteBuffer.wrap(src));
        bc.flush(true);

        // Now read and verify everything works.
        BufferedReadChannel brc = new BufferedReadChannel(new RandomAccessFile(tmpFile, "r").getChannel(), 64);
        // Verify that we wrote properly.
        assertTrue(brc.size() == cap);

        // This should read all the data
        dst = new byte[cap];
        dstBuff = ByteBuffer.wrap(dst);
        brc.clear();
        brc.read(dstBuff, 0);
        assertTrue(verifyData(src, dst, 0, dst.length));

        // Read only the last byte cap-1
        dst = new byte[1];
        dstBuff = ByteBuffer.wrap(dst);
        brc.clear();
        brc.read(dstBuff, cap-1);
        assertTrue(verifyData(src, dst, cap-1, dst.length));

        // Read some data, then read again with an overlap. Both reads should be smaller than read channel
        // capacity/2 so that the second read is served from the buffer.
        dst = new byte[16];
        dstBuff = ByteBuffer.wrap(dst);
        brc.clear();
        brc.read(dstBuff, 50);
        assertTrue(verifyData(src, dst, 50, dst.length));
        dst = new byte[16];
        dstBuff = ByteBuffer.wrap(dst);
        brc.read(dstBuff, 64);
        assertTrue(verifyData(src, dst, 64, dst.length));

        // Read data that partially overlaps
        dst = new byte[100];
        dstBuff = ByteBuffer.wrap(dst);
        brc.clear();
        brc.read(dstBuff, 500);
        assertTrue(verifyData(src, dst, 500, dst.length));
        dst = new byte[200];
        dstBuff = ByteBuffer.wrap(dst);
        brc.read(dstBuff, 580);
        assertTrue(verifyData(src, dst, 580, dst.length));

        // Read from the end of the file such that the readBuffer hits EOF
        dst = new byte[16];
        dstBuff = ByteBuffer.wrap(dst);
        brc.clear();
        brc.read(dstBuff, cap - 16);
        assertTrue(verifyData(src, dst, cap-16, dst.length));

        // Read from a position beyond the end of the file.
        try {
            dst = new byte[100];
            dstBuff = ByteBuffer.wrap(dst);
            brc.clear();
            brc.read(dstBuff, cap - 50);
            // Should not reach here.
            fail("Read from the end of the file.");
        } catch (IOException e) {
            // This is what we expect.
        }
    }

    //TODO: Modify this test when chunk calculation is dynamic.
    @Test
    public void testBufferedReorderedWriteChannel() throws Exception {
        int writeCap = 2048;
        int chunkSize = 128;
        int readCap = 128;
        BufferedReorderedWriteChannel channel
                = getReorderedWriteChannel(writeCap, readCap, chunkSize);
        // A write of size more than writeCap or writeCap - fakeceil should fail.
        try {
            channel.write(0, getBufferOfSize(writeCap + 1));
            fail("Write greater than write capacity did not throw an exception");
        } catch (IOException e) {
            // This is expected.
        }
        try {
            channel.write(0, getBufferOfSize(writeCap - BufferedReorderedWriteChannel.FAKE_CEILING_BYTES + 1));
            fail("Write greater than write capacity did not throw an exception");
        } catch (IOException e) {
            // This is expected.
        }

        // Test if writes to different keys go to different chunks.
        long base = 0;
        // All new keys should go to a new chunk.
        for (int i = 0; i < writeCap/chunkSize - 1; i++) {
            assertTrue("Misaligned first write.", channel.write(i, getBufferOfSize(10)) == base + i*128);
        }
        // All writes to the same keys should now go to the same chunk as before
        for (int i = 0; i < writeCap/chunkSize - 1; i++) {
            assertTrue("Misaligned second write.", channel.write(i, getBufferOfSize(10)) == base + 10 + i*128);
        }
        // A flush now should cause the pointer to be at 2048 - 128
        channel.flush(false);
        assertTrue("Misaligned position on first flush.", (base = channel.position()) == writeCap - chunkSize);

        // A write of writeCap - fakeceil should succeed.
        assertTrue("Could not write maximum number of bytes.", channel.write(0, getBufferOfSize(
                writeCap-BufferedReorderedWriteChannel.FAKE_CEILING_BYTES)) == base);
        // The position should have moved to base + writeCap
        assertTrue("Misaligned position on write.", channel.position() == base + writeCap);

        // A flush should not change the position.
        long prevPos = channel.position();
        channel.flush(false);
        assertTrue("Misaligned position on second flush.", (base = channel.position()) == prevPos);
        // A write asking for multiple chunks should succeed.
        assertTrue("Misaligned write.", channel.write(1, getBufferOfSize(chunkSize/2)) == base);
        // A write not crossing the fake ceiling should be in the same chunk
        assertTrue("Misaligned write.", channel.write(1, getBufferOfSize(
                chunkSize/2 - BufferedReorderedWriteChannel.FAKE_CEILING_BYTES)) == base + chunkSize/2);
        // We should not have allocated another chunk.
        assertTrue("Misaligned position.", channel.position() == base + chunkSize);

        prevPos = channel.position();
        channel.flush(false);
        assertTrue("Misaligned position on second flush.", (base = channel.position()) == prevPos);

        // A write which overflows the fake ceiling should start at a new chunk.
        assertTrue("Misaligned first write while testing overflow.", channel.write(1, getBufferOfSize(chunkSize/2)) == base);
        assertTrue("Misaligned second write while testing overflow.", channel.write(1, getBufferOfSize(chunkSize/2)) == base + chunkSize);

        channel.flush(false);
        base = channel.position();

        // A write that overflows the last chunk should result in a flush and new allocation. The
        // flush should change the filechannel's position to writeCap - chunkSize. The next write
        // should go at this position and allocate two chunks.
        for (int i = 0; i < writeCap/chunkSize - 1; i++) {
            assertTrue("Misaligned write.", channel.write(i, getBufferOfSize(10)) == base + i*128);
        }
        // Now try to write to a new key that does not fit in one chunk. The new write should take
        // up two chunks.
        long prevFilePos = channel.getFileChannelPosition();
        assertTrue("Misaligned write while testing last chunk overflow.", channel.write(
                writeCap/chunkSize-1, getBufferOfSize(chunkSize)) == base + writeCap - chunkSize);
        // File position should have changed because of the flush.
        assertTrue("File channel position did not change.", channel.getFileChannelPosition() == prevFilePos + writeCap
                - chunkSize);
        // Also, we should have allocated 2 chunks.
        base = channel.getFileChannelPosition();
        assertTrue("Did not allocate 2 chunks.", channel.position() == base + 2*chunkSize);
    }

    @Test
    public void testCorruptEntryLog() throws Exception {
        File tmpDir = File.createTempFile("bkTest", ".dir");
        tmpDir.delete();
        tmpDir.mkdir();
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        int gcWaitTime = 1000;
        ServerConfiguration conf = new ServerConfiguration();
        conf.setGcWaitTime(gcWaitTime);
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        // create some entries
        EntryLogger logger = new EntryLogger(conf);
        logger.addEntry(generateEntry(1, 1));
        logger.addEntry(generateEntry(3, 1));
        logger.addEntry(generateEntry(2, 1));
        logger.flush();
        // now lets truncate the file to corrupt the last entry, which simulates a partial write
        File f = new File(curDir, "0.log");
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        long lenNew = raf.length() - 10;
        raf.setLength(lenNew);
        raf.close();
        // now see which ledgers are in the log
        logger = new EntryLogger(conf);

        EntryLogMetadata meta = new EntryLogMetadata(0L);
        ExtractionScanner scanner = new ExtractionScanner(meta);

        try {
            logger.scanEntryLog(0L, scanner);
            fail("Should not reach here!");
        } catch (IOException ie) {
        }
        LOG.info("Extracted Meta From Entry Log {}", meta);
        System.out.println("Extracted Meta From Entry Log " + meta);
        assertNotNull(meta.ledgersMap.get(1L));
        assertNull(meta.ledgersMap.get(2L));
        assertNotNull(meta.ledgersMap.get(3L));
    }

    private ByteBuffer generateEntry(long ledger, long entry) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[64]);
        bb.putLong(ledger);
        bb.putLong(entry);
        bb.put(("ledger-" + ledger + "-" + entry).getBytes());
        bb.flip();
        return bb;
    }

    @Test
    public void testMissingLogId() throws Exception {
        File tmpDir = File.createTempFile("entryLogTest", ".dir");
        tmpDir.delete();
        tmpDir.mkdir();
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        ServerConfiguration conf = new ServerConfiguration();
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        // create some entries
        int numLogs = 3;
        int numEntries = 10;
        long[][] positions = new long[2*numLogs][];
        for (int i=0; i<numLogs; i++) {
            positions[i] = new long[numEntries];

            EntryLogger logger = new EntryLogger(conf);
            for (int j=0; j<numEntries; j++) {
                positions[i][j] = logger.addEntry(generateEntry(i, j));
            }
            logger.flush();
        }
        // delete last log id
        File lastLogId = new File(curDir, "lastId");
        lastLogId.delete();

        // write another entries
        for (int i=numLogs; i<2*numLogs; i++) {
            positions[i] = new long[numEntries];

            EntryLogger logger = new EntryLogger(conf);
            for (int j=0; j<numEntries; j++) {
                positions[i][j] = logger.addEntry(generateEntry(i, j));
            }
            logger.flush();
        }

        EntryLogger newLogger = new EntryLogger(conf);
        for (int i=0; i<(2*numLogs+1); i++) {
            File logFile = new File(curDir, Long.toHexString(i) + ".log");
            assertTrue(logFile.exists());
        }
        for (int i=0; i<2*numLogs; i++) {
            for (int j=0; j<numEntries; j++) {
                String expectedValue = "ledger-" + i + "-" + j;
                byte[] value = newLogger.readEntry(i, j, positions[i][j]);
                ByteBuffer buf = ByteBuffer.wrap(value);
                long ledgerId = buf.getLong();
                long entryId = buf.getLong();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                assertEquals(i, ledgerId);
                assertEquals(j, entryId);
                assertEquals(expectedValue, new String(data));
            }
        }
    }

    @Test
    /** Test that EntryLogger Should fail with FNFE, if entry logger directories does not exist*/
    public void testEntryLoggerShouldThrowFNFEIfDirectoriesDoesNotExist()
            throws Exception {
        File tmpDir = File.createTempFile("bkTest", ".dir");
        tmpDir.delete();
        ServerConfiguration conf = new ServerConfiguration();
        conf.setLedgerDirNames(new String[] { tmpDir.toString() });
        EntryLogger entryLogger = null;
        try {
            entryLogger = new EntryLogger(conf);
            fail("Expecting FileNotFoundException");
        } catch (FileNotFoundException e) {
            assertEquals("Entry log directory does not exist", e
                    .getLocalizedMessage());
        } finally {
            if (entryLogger != null) {
                entryLogger.shutdown();
            }
        }
    }

    @After
    public void tearDown() throws Exception {
    }

}
