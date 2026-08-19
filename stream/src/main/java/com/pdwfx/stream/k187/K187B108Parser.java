package com.pdwfx.stream.k187;

import com.pdwfx.stream.model.PdwRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单包解析（无 PDWHead1）。
 * <pre>
 *   [PDWHead3 48B | 可选填充 | FFHead 80B | FFData 109B × N]
 * </pre>
 * <ol>
 *   <li>校验 Head3.packHead == 0x7E8118E7</li>
 *   <li>解出 Head3 各字段；用 {@code infoType} 决定是否保留（见 {@link #INFO_TYPE_KEEP}）</li>
 *   <li>保留则继续解析 FFHead + N×FFData → {@link PdwRecord}</li>
 * </ol>
 */
public class K187B108Parser {

    private static final Logger log = LoggerFactory.getLogger(K187B108Parser.class);

    /** PDWHead3 结构长度 */
    public static final int HEAD3_LEN = 48;
    /** FFHead_B108H 结构长度 */
    public static final int FF_HEAD_LEN = 80;
    /** FFData_B108H 单条长度 */
    public static final int FF_DATA_LEN = 109;
    /** Head3 报文头标识 packHead */
    public static final int HEAD3_PACK_HEAD = 0x7E8118E7;
    /**
     * 保留的信息类别（Head3.infoType 低 16 位比较）。
     * 对端当前下发为 0xB107；经典定频 CDW 文档值为 0xB108。
     */
    public static final int INFO_TYPE_KEEP = 0xB107;
    /** @deprecated 使用 {@link #INFO_TYPE_KEEP} */
    public static final int INFO_TYPE_B108 = INFO_TYPE_KEEP;

    public static final class ParseStats {
        /** 进入解析的 TCP/缓冲包总数（含非保留 infoType） */
        public long packets;
        /** infoType 命中保留类型（当前 0xB107）的包数 */
        public long b108Packets;
        /** 成功产出并去重后的 PDW 条数 */
        public long records;
        /** DOA 融合失败或无效（doaMean=4000）而跳过的 FFData 条数 */
        public long skippedDoa;
        /** infoType 非保留而整包丢弃的次数 */
        public long skippedOtherType;
        /** packHead 魔数不匹配 */
        public long skippedBadMagic;
        /** curPackLen 大于实际缓冲长度 */
        public long skippedBadLength;
        /** packHead ≠ 0x7E8118E7 的累计次数（与 skippedBadMagic 同步递增） */
        public long head3PackHeadMismatch;
    }

    /** 最近一次 Head3 快照（status / 断点排查） */
    public static final class Head3Snapshot {
        /** 报文头魔数，期望 0x7E8118E7 */
        public int packHead;
        /** 当前包声明长度（含本头），单位字节 */
        public int curPackLen;
        /** 目的地址 */
        public int tgtAddr;
        /** 源地址 */
        public int srcAddr;
        /** 信息类别号；低 16 位与 INFO_TYPE_KEEP 比较决定是否保留 */
        public int infoType;
        /** 发报时间：高 32 位秒（相对 1970-1-1），低 32 位秒内 ns */
        public long infoTime;
        /** 流水序号 */
        public int seqNo;
        /** 总分包数 */
        public int packAmount;
        /** 当前包序号（1-based 语义依对端） */
        public int packNo;
        /** 报文内容总长（不含指令头），单位字节 */
        public int dataLen;
        /** 协议/软件版本字 */
        public int version;
        /** 实际收到的缓冲字节数 */
        public int bufferLength;
        /** infoTime 格式化为可读本地时间，失败时为 raw=0x… */
        public String infoTimeText;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "packHead=0x%08X curPackLen=%d infoType=0x%X(%d) seq=%d pack=%d/%d dataLen=%d ver=0x%04X tgt=%d src=%d infoTime=%s buf=%d",
                    packHead, curPackLen, infoType, infoType, seqNo, packNo, packAmount,
                    dataLen, version, tgtAddr, srcAddr, infoTimeText, bufferLength);
        }
    }

    private final ParseStats stats = new ParseStats();
    private final AtomicReference<Head3Snapshot> lastHead3 = new AtomicReference<>();

    public ParseStats getStats() { return stats; }
    public Head3Snapshot getLastHead3() { return lastHead3.get(); }

    /** 重启接收：清零解析计数与最近 Head3 快照。 */
    public void resetStats() {
        stats.packets = 0L;
        stats.b108Packets = 0L;
        stats.records = 0L;
        stats.skippedDoa = 0L;
        stats.skippedOtherType = 0L;
        stats.skippedBadMagic = 0L;
        stats.skippedBadLength = 0L;
        stats.head3PackHeadMismatch = 0L;
        lastHead3.set(null);
    }

    public List<PdwRecord> parsePacket(byte[] packet) {
        return parseHead3Packet(packet);
    }

    private List<PdwRecord> parseHead3Packet(byte[] packet) {
        List<PdwRecord> out = new ArrayList<>();
        if (packet == null || packet.length < HEAD3_LEN) {
            return out;
        }
        LittleEndian le = new LittleEndian(packet);

        // ---- PDWHead3（48 字节，与 cet36 _PDWHead3_S_Head 一致）----
        int packHead = le.readU32();       // 报文头，固定 0x7E8118E7
        int curPackLen = le.readU32();     // 当前包长度（含本头）
        int tgtAddr = le.readU16() & 0xffff;
        int srcAddr = le.readU16() & 0xffff;
        int infoType = le.readU32();       // 信息类别号 → 是否保留本包
        long infoTime = le.readU64();      // 发报时间（高32秒 / 低32 ns）
        int seqNo = le.readU32();
        int packAmount = le.readU32();
        int packNo = le.readU32();
        int dataLen = le.readU32();        // 报文内容总长（不含指令头）
        int version = le.readU16() & 0xffff;
        le.readBytes(6);                   // reserve

        Head3Snapshot snap = new Head3Snapshot();
        snap.packHead = packHead;
        snap.curPackLen = curPackLen;
        snap.tgtAddr = tgtAddr;
        snap.srcAddr = srcAddr;
        snap.infoType = infoType;
        snap.infoTime = infoTime;
        snap.seqNo = seqNo;
        snap.packAmount = packAmount;
        snap.packNo = packNo;
        snap.dataLen = dataLen;
        snap.version = version;
        snap.bufferLength = packet.length;
        snap.infoTimeText = formatInfoTime(infoTime);
        lastHead3.set(snap);

        stats.packets++;
        if (packHead != HEAD3_PACK_HEAD) {
            stats.skippedBadMagic++;
            stats.head3PackHeadMismatch++;
            return out;
        }
        if (curPackLen > 0 && curPackLen > packet.length) {
            stats.skippedBadLength++;
            return out;
        }

        if (log.isInfoEnabled() && (stats.packets <= 5 || stats.packets % 200 == 0)) {
            log.info("Head3: {}", snap);
        }

        // 按 Head3.infoType 过滤；非保留类型整包丢弃（不落盘）
        int type = infoType & 0xffff;
        if (type != INFO_TYPE_KEEP) {
            stats.skippedOtherType++;
            if (log.isDebugEnabled()) {
                log.debug("跳过包 Head3.infoType=0x{} (keep=0x{})",
                        Integer.toHexString(infoType).toUpperCase(Locale.ENGLISH),
                        Integer.toHexString(INFO_TYPE_KEEP).toUpperCase(Locale.ENGLISH));
            }
            return out;
        }

        stats.b108Packets++;
        LocalDateTime headTime = infoTimeToLocalDateTime(infoTime);
        // 对端在 Head3 与 FFHead 之间可能有固定填充，按联调结果跳过
        le.skipBytes(25);
        return parseFfBody(le, headTime, out);
    }

    /** 解析 FFHead + N×FFData，映射为落盘用 {@link PdwRecord} */
    private List<PdwRecord> parseFfBody(LittleEndian le, LocalDateTime headTime, List<PdwRecord> out) {

        if (le.remaining() < FF_HEAD_LEN) {
            return out;
        }
        // ---- FFHead（平台姿态与后续 FFData 条数）----
        int infoLength = le.readU32();          // 信息区长度
        le.readU64(); // taskId                 // 任务号（未使用）
        le.readU64(); // sFreq                  // 起频等（未使用）
        le.readU64(); // dK                     // 带宽/步进相关（未使用）
        LocalDateTime ffTime = readSystemTime(le); // 系统时：年…毫秒，共 8×u16
        le.readU64(); // fpga                   // FPGA 时标（未使用）
        int longitude = le.readU32();           // 平台经度，微度（÷1e6 → °）
        int latitude = le.readU32();            // 平台纬度，微度
        le.readU32(); // height                 // 高度
        int pitchRaw = le.readU32();            // 俯仰原始定点
        int rollRaw = le.readU32();             // 横滚原始定点
        le.readU16(); // speed                  // 地速
        int course = le.readU32();              // 航向，用于 DOA→真北修正
        int infoNum = le.readU16() & 0xffff;    // 后续 FFData 条数

        // 与 course÷100 → ° 同刻度（可被 StreamProperties.attitudeScale 覆盖时仅落盘侧再缩放）
        double pitchDeg = pitchRaw / 100.0;
        double rollDeg = rollRaw / 100.0;
        double courseDeg = course / 100.0;

        LocalDateTime baseTime = ffTime != null ? ffTime : headTime;
        Set<String> unique = new HashSet<>();   // 频点|方位|时刻 去重键
        for (int i = 0; i < infoNum; i++) {
            if (le.remaining() < FF_DATA_LEN) {
                break;
            }
            // ---- FFData 单条（109B）----
            int qTsc = le.readU32();            // 信号起始相对时，单位 10µs → nSignalStartTime
            long pLzx = le.readU64();           // 载频 Hz → freqMhz
            int zLsj = le.readU32();            // 驻留计数，单位 10µs → nSignalTime
            short gMdk = le.readU16();          // 带宽 Hz → signalBwKhz
            short fD = le.readU16();            // 幅度 dB → signalLevelDb
            le.readU16(); // fWgs               // 未用
            short[] fw = le.readU16Array(28);   // 28 路 DOA 直方图样本（0.1°）
            le.readBytes(28);                   // 预留
            le.readU8();
            le.readU8();
            le.readU8();

            List<Short> fwList = new ArrayList<>(28);
            for (int j = 0; j < 28; j++) {
                fwList.add(fw[j]);
            }
            // 主峰融合；rangeCountTh=21 与 cet36 一致；失败则丢弃本条
            DoaHistChiefZhang.Result doa = DoaHistChiefZhang.fuse(fwList, pLzx / 1000L, 21);
            if (doa.code != 0 || doa.doaMean == 4000) {
                stats.skippedDoa++;
                continue;
            }
            short xhfw01 = DoaHistChiefZhang.toTrueAzimuth(doa.doaMean, course);
            LocalDateTime zcsj = baseTime;
            if (zcsj != null) {
                // 联调：基准时 +8h，再叠加 qTsc×10µs
                zcsj = zcsj.plusHours(8).plusNanos((long) qTsc * 10_000L);
            }

            PdwRecord rec = new PdwRecord();
            rec.setFreqMhz(pLzx / 1_000_000.0);
            rec.setSignalBwKhz(gMdk / 1000.0);
            rec.setSignalLevelDb(fD);
            rec.setAzimuthDeg(xhfw01 / 10.0);
            rec.setDetectTime(zcsj);
            rec.setLongitude(longitude / 1_000_000.0);
            rec.setLatitude(latitude / 1_000_000.0);
            rec.setNSignalTime10us(zLsj & 0xffffffffL);
            rec.setNSignalStartTime10us(qTsc & 0xffffffffL);
            rec.setPitchDeg(pitchDeg);
            rec.setRollDeg(rollDeg);
            rec.setCourseDeg(courseDeg);

            String key = rec.getFreqMhz() + "|" + rec.getAzimuthDeg() + "|" + rec.getDetectTime();
            if (unique.add(key)) {
                out.add(rec);
                stats.records++;
            }
        }
        if (log.isInfoEnabled() && (stats.b108Packets <= 5 || stats.b108Packets % 200 == 0)) {
            log.info("FFBody infoLength={} infoNum={} outRecords={} remaining={}",
                    infoLength, infoNum, out.size(), le.remaining());
        }
        return out;
    }

    /** Head3.infoTime：高 32 位为相对 1970-1-1 的秒，低 32 位为秒内计数（ns） */
    private static LocalDateTime infoTimeToLocalDateTime(long infoTime) {
        long sec = (infoTime >>> 32) & 0xffffffffL;
        long ns = infoTime & 0xffffffffL;
        if (sec < 1_000_000_000L || sec > 4_000_000_000L) {
            return null;
        }
        if (ns >= 1_000_000_000L) {
            ns = 0;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(sec, ns), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatInfoTime(long infoTime) {
        LocalDateTime t = infoTimeToLocalDateTime(infoTime);
        return t != null ? t.toString() : ("raw=0x" + Long.toHexString(infoTime));
    }

    private static LocalDateTime readSystemTime(LittleEndian le) {
        int year = le.readU16() & 0xffff;
        int month = le.readU16() & 0xffff;
        le.readU16();
        int day = le.readU16() & 0xffff;
        int hour = le.readU16() & 0xffff;
        int minute = le.readU16() & 0xffff;
        int second = le.readU16() & 0xffff;
        int ms = le.readU16() & 0xffff;
        if (year < 1970 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, ms * 1_000_000);
        } catch (Exception e) {
            return null;
        }
    }
}
