package one.nio.mem;

public class LongLongHashMap extends LongHashSet {
    protected long values;

    public LongLongHashMap(int capacity) {
        super(capacity);
        this.values = DirectMemory.allocateAndFill((long) this.capacity * 8, this, (byte) 0);
    }

    public LongLongHashMap(int capacity, long keys, long values) {
        super(capacity, keys);
        this.values = values;
    }

    public long get(long key) {
        int index = getKey(key);
        return index >= 0 ? valueAt(index) : 0;
    }

    public void put(long key, long value) {
        int index = putKey(key);
        setValueAt(index, value);
    }

    public long putIfAbsent(long key, long value) {
        int step = 1;
        int index = hash(key) % capacity;

        do {
            long cur = keyAt(index);
            if (cur == EMPTY) {
                if (!unsafe.compareAndSwapLong(null, keys + (long) index * 8, cur, key)) {
                    continue;
                }
                setValueAt(index, value);
                incrementSize();
                return 0;
            } else if (cur == key) {
                return valueAt(index);
            }

            if ((index += step) >= capacity) index -= capacity;
        } while (++step <= maxSteps);

        throw new OutOfMemoryException("No room for a new key");
    }

    public boolean replace(long key, long oldValue, long newValue) {
        int index = getKey(key);
        return index >= 0 && unsafe.compareAndSwapLong(null, values + (long) index * 8, oldValue, newValue);
    }

    public long replace(long key, long newValue) {
        int index = putKey(key);
        return replaceValueAt(index, newValue);
    }

    public long adjustOrPut(long key, long delta) {
        int index = putKey(key);
        return adjustValueAt(index, delta);
    }

    public long remove(long key) {
        int index = getKey(key);
        return index >= 0 ? replaceValueAt(index, 0) : 0;
    }

    public final long valueAt(int index) {
        return unsafe.getLong(values + (long) index * 8);
    }

    public final void setValueAt(int index, long value) {
        unsafe.putLong(values + (long) index * 8, value);
    }

    public final long replaceValueAt(int index, long newValue) {
        long address = values + (long) index * 8;
        for (;;) {
            long oldValue = unsafe.getLong(address);
            if (unsafe.compareAndSwapLong(null, address, oldValue, newValue)) {
                return oldValue;
            }
        }
    }

    public final long adjustValueAt(int index, long delta) {
        long address = values + (long) index * 8;
        for (;;) {
            long oldValue = unsafe.getLong(address);
            long newValue = oldValue + delta;
            if (unsafe.compareAndSwapLong(null, address, oldValue, newValue)) {
                return newValue;
            }
        }
    }

    @Override
    public void clear() {
        super.clear();
        unsafe.setMemory(values, (long) capacity * 8, (byte) 0);
    }

    public static long sizeInBytes(int capacity) {
        return (long) capacity * 16;
    }
}
