package com.pdwfx.stream.k187;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 小端二进制读取（对齐 cet36 StructSerializer 常用布局）。 */
public final class LittleEndian {
    private final ByteBuffer buf;

    public LittleEndian(byte[] data) {
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    public LittleEndian(byte[] data, int offset, int length) {
        this.buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int position() { return buf.position(); }
    public void position(int p) { buf.position(p); }
    public int remaining() { return buf.remaining(); }

    public byte readU8() { return buf.get(); }
    public short readU16() { return buf.getShort(); }
    public int readU32() { return buf.getInt(); }
    public long readU64() { return buf.getLong(); }

    public short[] readU16Array(int n) {
        short[] a = new short[n];
        for (int i = 0; i < n; i++) a[i] = buf.getShort();
        return a;
    }

    public byte[] readBytes(int n) {
        byte[] a = new byte[n];
        buf.get(a);
        return a;
    }
}
