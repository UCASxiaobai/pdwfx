package com.pdwfx.stream.k187;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 小端二进制读取（对齐 cet36 StructSerializer 常用布局）。
 * <p>position/limit 必须经 {@link Buffer} 调用：JDK9+ 在 ByteBuffer 上covariant 重写了这些方法，
 * 若用高版本 JDK 编译却在 JDK8 运行，会 NoSuchMethodError。</p>
 */
public final class LittleEndian {
    /** 底层小端缓冲；读写 position 须经 {@link Buffer} 转型以兼容 JDK8 */
    private final ByteBuffer buf;

    public LittleEndian(byte[] data) {
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    public LittleEndian(byte[] data, int offset, int length) {
        this.buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int position() { return buf.position(); }
    public void position(int p) { ((Buffer) buf).position(p); }
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

    public void skipBytes(int n) {
        ((Buffer) buf).position(buf.position() + n);
    }
}
