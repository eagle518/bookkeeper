/*
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Provides a buffering layer in front of a FileChannel.
 */

/**
 * TODO: Rename this to a BufferedReadWriteChannel?
 */
public class BufferedChannel extends BufferedReadChannel {
    // The capacity of the write buffer.
    protected final int writeCapacity;
    // The position of the file channel's write pointer.
    protected volatile long writeBufferStartPosition;
    // The buffer used to write operations.
    protected final ByteBuffer writeBuffer;
    // The absolute position of the next write operation.
    protected volatile long position;

    // make constructor to be public for unit test
    public BufferedChannel(FileChannel fc, int capacity) throws IOException {
        // Use the same capacity for read and write buffers.
        this(fc, capacity, capacity);
    }

    public BufferedChannel(FileChannel fc, int writeCapacity, int readCapacity) throws IOException {
        super(fc, readCapacity);
        // Set the read buffer's limit to readCapacity.
        this.readBuffer.limit(readCapacity);
        this.writeCapacity = writeCapacity;
        this.position = fc.position();
        this.writeBufferStartPosition = position;
        this.writeBuffer = ByteBuffer.allocateDirect(writeCapacity);
    }

    /**
     * Write all the data in src to the {@link FileChannel}. Note that this function can
     * buffer or re-order writes based on the implementation. These writes will be flushed
     * to the disk only when flush() is invoked.
     *
     * @param src The source ByteBuffer which contains the data to be written.
     * @throws IOException if a write operation fails.
     */
    synchronized public void write(ByteBuffer src) throws IOException {
        int copied = 0;
        while(src.remaining() > 0) {
            int truncated = 0;
            if (writeBuffer.remaining() < src.remaining()) {
                truncated = src.remaining() - writeBuffer.remaining();
                src.limit(src.limit()-truncated);
            }
            copied += src.remaining();
            writeBuffer.put(src);
            src.limit(src.limit()+truncated);
            // if we have run out of buffer space, we should flush to the file
            if (writeBuffer.remaining() == 0) {
                flushInternal();
            }
        }
        position += copied;
    }

    /**
     * Get the position where the next write operation will begin writing from.
     * @return
     */
    public long position() {
        return position;
    }

    /**
     * Get the position of the file channel's write pointer.
     * @return
     */
    public long getFileChannelPosition() {
        return writeBufferStartPosition;
    }


    /**
     * Write any data in the buffer to the file. If sync is set to true, force a sync operation so that
     * data is persisted to the disk.
     * @param shouldForceWrite
     * @throws IOException if the write or sync operation fails.
     */
    public void flush(boolean shouldForceWrite) throws IOException {
        synchronized(this) {
            flushInternal();
        }
        if (shouldForceWrite) {
            forceWrite();
        }
    }

    /**
     * Write any data in the buffer to the file and advance the writeBufferPosition
     * Callers are expected to synchronize appropriately
     * @throws IOException if the write fails.
     */
    private void flushInternal() throws IOException {
        writeBuffer.flip();
        fileChannel.write(writeBuffer);
        writeBuffer.clear();
        writeBufferStartPosition = fileChannel.position();
    }

    public void forceWrite() throws IOException {
        fileChannel.force(false);
    }

    @Override
    synchronized public int read(ByteBuffer dest, long pos) throws IOException {
        long prevPos = pos;
        while(dest.remaining() > 0) {
            // check if it is in the write buffer
            if (writeBuffer != null && writeBufferStartPosition <= pos) {
                long positionInBuffer = pos - writeBufferStartPosition;
                long bytesToCopy = writeBuffer.position()-positionInBuffer;
                if (bytesToCopy > dest.remaining()) {
                    bytesToCopy = dest.remaining();
                }
                if (bytesToCopy == 0) {
                    throw new IOException("Read past EOF");
                }
                ByteBuffer src = writeBuffer.duplicate();
                src.position((int) positionInBuffer);
                src.limit((int) (positionInBuffer+bytesToCopy));
                dest.put(src);
                pos+= bytesToCopy;
            } else if (writeBuffer == null && writeBufferStartPosition <= pos) {
                // here we reach the end
                break;
                // first check if there is anything we can grab from the readBuffer
            } else if (readBufferStartPosition <= pos && pos < readBufferStartPosition+readBuffer.capacity()) {
                long positionInBuffer = pos - readBufferStartPosition;
                long bytesToCopy = readBuffer.capacity()-positionInBuffer;
                if (bytesToCopy > dest.remaining()) {
                    bytesToCopy = dest.remaining();
                }
                ByteBuffer src = readBuffer.duplicate();
                src.position((int) positionInBuffer);
                src.limit((int) (positionInBuffer+bytesToCopy));
                dest.put(src);
                pos += bytesToCopy;
                // let's read it
            } else {
                readBufferStartPosition = pos;
                readBuffer.clear();
                // make sure that we don't overlap with the write buffer
                if (readBufferStartPosition + readBuffer.capacity() >= writeBufferStartPosition) {
                    readBufferStartPosition = writeBufferStartPosition - readBuffer.capacity();
                    if (readBufferStartPosition < 0) {
                        readBuffer.put(LedgerEntryPage.zeroPage, 0, (int)-readBufferStartPosition);
                    }
                }
                while(readBuffer.remaining() > 0) {
                    if (fileChannel.read(readBuffer, readBufferStartPosition+readBuffer.position()) <= 0) {
                        throw new IOException("Short read");
                    }
                }
                readBuffer.put(LedgerEntryPage.zeroPage, 0, readBuffer.remaining());
                readBuffer.clear();
            }
        }
        return (int)(pos - prevPos);
    }

    @Override
    synchronized public void clear() {
        super.clear();
        writeBuffer.clear();
    }
}
