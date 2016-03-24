/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.pagemem.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.apache.ignite.internal.pagemem.FullPageId;
import org.apache.ignite.internal.pagemem.Page;
import org.apache.ignite.internal.util.typedef.internal.SB;

/**
 *
 */
public class PageImpl extends AbstractQueuedSynchronizer implements Page {
    /** */
    private static final AtomicIntegerFieldUpdater<PageImpl> refCntUpd =
        AtomicIntegerFieldUpdater.newUpdater(PageImpl.class, "refCnt");

    /** */
    private final FullPageId fullId;

    /** */
    public final long ptr;

    /** */
    @SuppressWarnings("unused")
    private volatile int refCnt;

    /** */
    private volatile int ver;

    /** */
    private final ByteBuffer buf;

    /** */
    private final PageMemoryImpl pageMem;

    /**
     * @param fullId Full page ID.
     * @param pageMem Page memory.
     */
    public PageImpl(FullPageId fullId, long ptr, PageMemoryImpl pageMem) {
        this.fullId = fullId;
        this.ptr = ptr;
        this.pageMem = pageMem;

        buf = pageMem.wrapPointer(ptr + PageMemoryImpl.PAGE_OVERHEAD, pageMem.pageSize());
    }

    /** {@inheritDoc} */
    @Override public int version() {
        return ver;
    }

    /** {@inheritDoc} */
    @Override public int incrementVersion() {
        return ++ver; // Must be updated in write lock.
    }

    /**
     * @param buf Byte buffer.
     * @return The given buffer back.
     */
    private ByteBuffer reset(ByteBuffer buf) {
        buf.order(ByteOrder.nativeOrder());

        buf.position(0);
        buf.limit(pageMem.pageSize());

        return buf;
    }

    /** {@inheritDoc} */
    @Override public long id() {
        return fullId.pageId();
    }

    /** {@inheritDoc} */
    @Override public FullPageId fullId() {
        return fullId;
    }

    /** {@inheritDoc} */
    @Override protected boolean tryAcquire(int ignore) {
        return compareAndSetState(0, -1);
    }

    /** {@inheritDoc} */
    @Override protected boolean tryRelease(int ignore) {
        boolean res = compareAndSetState(-1, 0);

        assert res : "illegal monitor state";

        return res;
    }

    /** {@inheritDoc} */
    @Override protected int tryAcquireShared(int ignore) {
        for (;;) {
            int state = getState();

            if (state == -1 || hasQueuedPredecessors()) // TODO we are fair here, may be this an overkill
                return -1;

            if (compareAndSetState(state, state + 1))
                return 1;
        }
    }

    /** {@inheritDoc} */
    @Override protected boolean tryReleaseShared(int ignore) {
        for (;;) {
            int state = getState();

            if (state <= 0)
                throw new IllegalMonitorStateException("State: " + state);

            if (compareAndSetState(state, state - 1))
                return true;
        }
    }

    /** {@inheritDoc} */
    @Override public ByteBuffer getForRead() {
        acquireShared(1);

        return reset(buf.asReadOnlyBuffer());
    }

    /** {@inheritDoc} */
    @Override public void releaseRead() {
        releaseShared(1);
    }

    /** {@inheritDoc} */
    @Override public ByteBuffer getForInitialWrite() {
        assert getExclusiveOwnerThread() == null;
        assert getState() == 0;

        markDirty();

        return reset(buf);
    }

    /** {@inheritDoc} */
    @Override public ByteBuffer getForWrite() {
        Thread th = Thread.currentThread();

        if (getExclusiveOwnerThread() != th) {
            acquire(1);

            setExclusiveOwnerThread(th);
        }

        return reset(buf);
    }

    /**
     * Mark dirty.
     */
    private void markDirty() {
        pageMem.setDirty(fullId, ptr, true);
    }

    /** {@inheritDoc} */
    @Override public void releaseWrite(boolean markDirty) {
        if (markDirty)
            markDirty();

        assert getExclusiveOwnerThread() == Thread.currentThread() : "illegal monitor state";

        setExclusiveOwnerThread(null);

        release(1);
    }

    /**
     * @return {@code true} If this page is not acquired neither for read nor for write.
     */
    public boolean isFree() {
        return getState() == 0;
    }

    /** {@inheritDoc} */
    @Override public boolean isDirty() {
        return pageMem.isDirty(ptr);
    }

    @Override public String toString() {
        SB sb = new SB("PageImpl [handle=");

        sb.a(fullId);
        sb.a(", relPtr=");
        sb.appendHex(pageMem.readRelative(ptr));
        sb.a(", absPtr=");
        sb.appendHex(ptr);
        sb.a(']');

        return sb.toString();
    }

    /**
     * Increments reference count. This method is invoked from the PageMemoryImpl segment read lock, so it
     * must be synchronized.
     */
    void acquireReference() {
        refCntUpd.incrementAndGet(this);
    }

    /**
     * Checks if page is acquired by a thread.
     */
    boolean isAcquired() {
        return refCnt != 0;
    }

    /**
     * @return {@code True} if last reference has been released. This method is invoked
     */
    boolean releaseReference() {
        int refs = refCntUpd.decrementAndGet(this);

        assert refs >= 0;

        return refs == 0;
    }

    /** {@inheritDoc} */
    @Override public void close() {
        pageMem.releasePage(this);
    }
}