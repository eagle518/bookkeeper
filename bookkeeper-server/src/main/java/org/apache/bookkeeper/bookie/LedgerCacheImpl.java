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
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.CopyOnWriteArraySet;

import com.google.common.annotations.VisibleForTesting;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.meta.ActiveLedgerManager;
import org.apache.bookkeeper.stats.StatsLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of LedgerCache interface.
 * This class serves two purposes.
 */
public class LedgerCacheImpl implements LedgerCache {
    private final static Logger LOG = LoggerFactory.getLogger(LedgerCacheImpl.class);
    static final String IDX = ".idx";
    static final String RLOC = ".rloc";

    private final IndexInMemPageMgr indexPageManager;
    final IndexPersistenceMgr indexPersistenceManager;
    final int pageSize;
    final int entriesPerPage;
    final CopyOnWriteArraySet<LedgerStorageListener> listeners;

    public LedgerCacheImpl(ServerConfiguration conf,
                           ActiveLedgerManager alm,
                           LedgerDirsManager ledgerDirsManager,
                           StatsLogger statsLogger)
            throws IOException {
        this.pageSize = conf.getPageSize();
        this.entriesPerPage = pageSize / LedgerEntryPage.getIndexEntrySize();
        this.indexPersistenceManager =
                new IndexPersistenceMgr(pageSize, entriesPerPage, conf, alm, ledgerDirsManager, statsLogger);
        this.indexPageManager =
                new IndexInMemPageMgr(pageSize, entriesPerPage, conf, indexPersistenceManager, statsLogger);
        this.listeners = new CopyOnWriteArraySet<LedgerStorageListener>();

        LOG.info("maxMemory = {}", Runtime.getRuntime().maxMemory());
        LOG.info("PageSize is {}", pageSize);
    }

    @Override
    public void registerListener(LedgerStorageListener listener) {
        this.listeners.add(listener);
    }

    IndexInMemPageMgr getIndexPageManager() {
        return indexPageManager;
    }

    /**
     * @return page size used in ledger cache
     */
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public Long getLastAddConfirmed(long ledgerId) throws IOException {
        return indexPersistenceManager.getLastAddConfirmed(ledgerId);
    }

    @Override
    public long updateLastAddConfirmed(long ledgerId, long lac) throws IOException {
        return indexPersistenceManager.updateLastAddConfirmed(ledgerId, lac);
    }

    @Override
    public Observable waitForLastAddConfirmedUpdate(long ledgerId, long previoisLAC, Observer observer) throws IOException {
        return indexPersistenceManager.waitForLastAddConfirmedUpdate(ledgerId, previoisLAC, observer);
    }


    @Override
    public void putEntryOffset(long ledger, long entry, long offset) throws IOException {
        indexPageManager.putEntryOffset(ledger, entry, offset);
    }

    @Override
    public long getEntryOffset(long ledger, long entry) throws IOException {
        return indexPageManager.getEntryOffset(ledger, entry);
    }

    @VisibleForTesting
    public static final String getLedgerName(long ledgerId) {
        int parent = (int) (ledgerId & 0xff);
        int grandParent = (int) ((ledgerId & 0xff00) >> 8);
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(grandParent));
        sb.append('/');
        sb.append(Integer.toHexString(parent));
        sb.append('/');
        sb.append(Long.toHexString(ledgerId));
        sb.append(IDX);
        return sb.toString();
    }

    @Override
    public void flushLedger(boolean doAll) throws IOException {
        indexPageManager.flushOneOrMoreLedgers(doAll);
    }

    @Override
    public long getLastEntry(long ledgerId) throws IOException {
        // Get the highest entry from the pages that are in memory
        long lastEntryInMem = indexPageManager.getLastEntryInMem(ledgerId);
        // Some index pages may have been evicted from memory, retrieve the last entry
        // from the persistent store. We will check if there could be an entry beyond the
        // last in mem entry and only then attempt to get the last persisted entry from the file
        // The latter is just an optimization
        long lastEntry = indexPersistenceManager.getPersistEntryBeyondInMem(ledgerId, lastEntryInMem);
        return lastEntry;
    }

    /**
     * This method is called whenever a ledger is deleted by the BookKeeper Client
     * and we want to remove all relevant data for it stored in the LedgerCache.
     */
    @Override
    public void deleteLedger(long ledgerId) throws IOException {
        LOG.debug("Deleting ledgerId: {}", ledgerId);

        indexPageManager.removePagesForLedger(ledgerId);
        indexPersistenceManager.removeLedger(ledgerId);

        // when a ledger is deleted, notify by listeners
        for (LedgerStorageListener listener : listeners) {
            listener.onLedgerDeleted(ledgerId);
        }
    }

    @Override
    public byte[] readMasterKey(long ledgerId) throws IOException, BookieException {
        return indexPersistenceManager.readMasterKey(ledgerId);
    }

    @Override
    public boolean setFenced(long ledgerId) throws IOException {
        return indexPersistenceManager.setFenced(ledgerId);
    }

    @Override
    public boolean isFenced(long ledgerId) throws IOException {
        return indexPersistenceManager.isFenced(ledgerId);
    }

    @Override
    public void setMasterKey(long ledgerId, byte[] masterKey) throws IOException {
        indexPersistenceManager.setMasterKey(ledgerId, masterKey);
    }

    @Override
    public boolean ledgerExists(long ledgerId) throws IOException {
        return indexPersistenceManager.ledgerExists(ledgerId);
    }

    @Override
    public void close() throws IOException {
        indexPersistenceManager.close();
    }
}
