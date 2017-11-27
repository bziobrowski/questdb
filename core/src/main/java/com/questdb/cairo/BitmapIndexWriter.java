/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cairo;

import com.questdb.std.FilesFacade;
import com.questdb.std.Misc;
import com.questdb.std.Unsafe;
import com.questdb.std.str.Path;

import java.io.Closeable;
import java.io.IOException;

public class BitmapIndexWriter implements Closeable {
    private final ReadWriteMemory keyMem;
    private final ReadWriteMemory valueMem;
    private final int blockCapacity;
    private final int blockValueCountMod;
    private long valueMemSize = 0;
    private long keyCount;

    public BitmapIndexWriter(FilesFacade ff, CharSequence root, CharSequence name, int blockCapacity) {
        long pageSize = TableUtils.getMapPageSize(ff);

        try (Path path = new Path()) {
            BitmapIndexConstants.keyFileName(path, root, name);

            boolean exists = ff.exists(path);
            this.keyMem = new ReadWriteMemory(ff, path, pageSize);
            if (!exists) {
                initKeyMemory(blockCapacity);
            }

            long keyMemSize = this.keyMem.size();
            // check if key file header is present
            if (keyMemSize < BitmapIndexConstants.KEY_FILE_RESERVED) {
                throw CairoException.instance(0).put("key file is too small");
            }

            // verify header signature
            if (this.keyMem.getByte(BitmapIndexConstants.KEY_RESERVED_OFFSET_SIGNATURE) != BitmapIndexConstants.SIGNATURE) {
                throw CairoException.instance(0).put("invalid header");
            }

            // verify key count
            this.keyCount = this.keyMem.getLong(BitmapIndexConstants.KEY_RESERVED_OFFSET_KEY_COUNT);
            if (keyMemSize < keyMemSize()) {
                throw CairoException.instance(0).put("truncated key file");
            }

            this.valueMemSize = this.keyMem.getLong(BitmapIndexConstants.KEY_RESERVED_OFFSET_VALUE_MEM_SIZE);

            BitmapIndexConstants.valueFileName(path, root, name);

            this.valueMem = new ReadWriteMemory(ff, path, pageSize);

            if (this.valueMem.size() < this.valueMemSize) {
                throw CairoException.instance(0).put("truncated value file");
            }

            // block value count is always a power of two
            // to calculate remainder we use faster 'x & (count-1)', which is equivalent to (x % count)
            this.blockValueCountMod = this.keyMem.getInt(BitmapIndexConstants.KEY_RESERVED_OFFSET_BLOCK_VALUE_COUNT) - 1;
            this.blockCapacity = this.blockValueCountMod + 1 + BitmapIndexConstants.VALUE_BLOCK_FILE_RESERVED;
        }
    }

    /**
     * Adds key-value pair to index. If key already exists, value is appended to end of list of existing values. Otherwise
     * new value list is associated with the key.
     * <p>
     * Index is updated atomically as far as concurrent reading is concerned. Please refer to notes on classes that
     * are responsible for reading bitmap indexes, such as {@link BitmapIndexBackwardReader}.
     *
     * @param key   int key
     * @param value long value
     */
    public void add(int key, long value) {
        long offset = BitmapIndexConstants.getKeyEntryOffset(key);
        if (key < keyCount) {
            // when key exists we have possible outcomes with regards to values
            // 1. last value block has space if value cell index is not the last in block
            // 2. value block is full and we have to allocate a new one
            // 3. value count is 0. This means key was created as byproduct of adding sparse key value
            // second option is supposed to be less likely because we attempt to
            // configure block capacity to accommodate as many values as possible
            long valueBlockOffset = keyMem.getLong(offset + BitmapIndexConstants.KEY_ENTRY_OFFSET_LAST_VALUE_BLOCK_OFFSET);
            long valueCount = keyMem.getLong(offset + BitmapIndexConstants.KEY_ENTRY_OFFSET_VALUE_COUNT);
            int valueCellIndex = (int) (valueCount & blockValueCountMod);
            if (valueCellIndex > 0) {
                // this is scenario #1: key exists and there is space in last block to add value
                // we don't need to allocate new block, just add value and update value count on key
                appendValue(offset, valueBlockOffset, valueCount, valueCellIndex, value);
            } else if (valueCount == 0) {
                // this is scenario #3: we are effectively adding a new key and creating new block
                initValueBlockAndStoreValue(offset, value);
            } else {
                // this is scenario #2: key exists but last block is full. We need to create new block and add value there
                addVaueBlockAndStoreValue(offset, valueBlockOffset, valueCount, value);
            }
        } else {
            // This is a new key. Because index can have sparse keys whenever we think "key exists" we must deal
            // with holes left by this branch, which allocates new key. All key entries that have been
            // skipped during creation of new key will have been initialized with zeroes. This includes counts and
            // block offsets.
            initValueBlockAndStoreValue(offset, value);
            // here we also need to update key count
            // we don't just increment key count, in case this addition creates sparse key set
            updateKeyCount(key);
        }
    }

    @Override
    public void close() throws IOException {
        keyMem.jumpTo(keyMemSize());
        valueMem.jumpTo(valueMemSize);
        Misc.free(keyMem);
        Misc.free(valueMem);
    }

    private void addVaueBlockAndStoreValue(long offset, long valueBlockOffset, long valueCount, long value) {
        long newValueBlockOffset = allocateValueBlockAndStore(value);

        // update block linkage before we increase count
        // this is important to index readers, which will act on value count they read

        // we subtract 8 because we just written long value
        valueMem.skip(blockCapacity - 8 - BitmapIndexConstants.VALUE_BLOCK_FILE_RESERVED);
        // set previous block offset on new block
        valueMem.putLong(valueBlockOffset);
        // set next block offset on previous block
        valueMem.jumpTo(valueBlockOffset - BitmapIndexConstants.VALUE_BLOCK_FILE_RESERVED + 8);
        valueMem.putLong(newValueBlockOffset);

        // update count and last value block offset for the key
        // in atomic fashion
        keyMem.jumpTo(offset);
        keyMem.putLong(valueCount + 1);
        Unsafe.getUnsafe().storeFence();

        // don't set first block offset here
        // it would have been done when this key was first created
        keyMem.skip(8);

        // write last block offset because it changed in this scenario
        keyMem.putLong(valueBlockOffset);
        Unsafe.getUnsafe().storeFence();

        // write count check
        keyMem.putLong(valueCount + 1);
        Unsafe.getUnsafe().storeFence();

        // we are done adding value to new block of values
    }

    private long allocateValueBlockAndStore(long value) {
        long newValueBlockOffset = valueMemSize;
        valueMemSize += blockCapacity;

        // must update value mem size in key memory header
        // so that index can be opened correctly next time it loads
        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_SEQUENCE);
        long seq = keyMem.getLong(BitmapIndexConstants.KEY_RESERVED_SEQUENCE) + 1;
        keyMem.putLong(seq);
        Unsafe.getUnsafe().storeFence();

        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_OFFSET_VALUE_MEM_SIZE);
        keyMem.putLong(valueMemSize);
        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_SEQUENCE_CHECK);
        Unsafe.getUnsafe().storeFence();
        keyMem.putLong(seq);

        // store our value
        valueMem.jumpTo(newValueBlockOffset);
        valueMem.putLong(value);
        return newValueBlockOffset;
    }

    private void appendValue(long offset, long valueBlockOffset, long valueCount, int valueCellIndex, long value) {
        // first set value
        valueMem.jumpTo(valueBlockOffset + valueCellIndex * 8);
        valueMem.putLong(value);

        // update count and last value block offset for the key
        // in atomic fashion
        keyMem.jumpTo(offset);
        keyMem.putLong(valueCount + 1);

        // don't change block offsets here
        keyMem.skip(16);

        // write count check
        keyMem.putLong(valueCount + 1);

        // we only set fence at the end because we don't care in which order these counts are updated
        // nothing in between has changed anyway
//                Unsafe.getUnsafe().storeFence();
    }

    private void initKeyMemory(int blockValueCount) {
        keyMem.putByte(BitmapIndexConstants.SIGNATURE);
        keyMem.putLong(1); // SEQUENCE
        Unsafe.getUnsafe().storeFence();
        keyMem.putLong(0); // VALUE MEM SIZE
        keyMem.putInt(blockValueCount); // BLOCK VALUE COUNT
        keyMem.putLong(0); // KEY COUNT
        Unsafe.getUnsafe().storeFence();
        keyMem.putLong(1); // SEQUENCE CHECK
    }

    private void initValueBlockAndStoreValue(long offset, long value) {
        long newValueBlockOffset = allocateValueBlockAndStore(value);

        // don't need to update linkage, value count is less than block size
        // index readers must not access linkage information in this case

        // now update key entry in atomic fashion
        // update count and last value block offset for the key
        // in atomic fashion
        keyMem.jumpTo(offset);
        keyMem.putLong(1);
        Unsafe.getUnsafe().storeFence();

        // first and last blocks are the same
        keyMem.putLong(newValueBlockOffset);
        keyMem.putLong(newValueBlockOffset);
        Unsafe.getUnsafe().storeFence();

        // write count check
        keyMem.putLong(1);
        Unsafe.getUnsafe().storeFence();
    }

    private long keyMemSize() {
        return this.keyCount * BitmapIndexConstants.KEY_ENTRY_SIZE + BitmapIndexConstants.KEY_FILE_RESERVED;
    }

    private void updateKeyCount(int key) {
        keyCount = key + 1;

        // also write key count to header of key memory
        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_SEQUENCE);
        long seq = keyMem.getLong(BitmapIndexConstants.KEY_RESERVED_SEQUENCE) + 1;
        keyMem.putLong(seq);
        Unsafe.getUnsafe().storeFence();

        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_OFFSET_KEY_COUNT);
        keyMem.putLong(keyCount);
        Unsafe.getUnsafe().storeFence();

        keyMem.jumpTo(BitmapIndexConstants.KEY_RESERVED_SEQUENCE_CHECK);
        keyMem.putLong(seq);
    }
}