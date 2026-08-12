package com.pdwfx.stream.k187;

import com.pdwfx.stream.model.PdwRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * K187 PDW 包解析（仅 B108 定频）。
 * <p>包布局：Head1(34) + Head3(48) + payload；B108 payload = FFHead(80) + N×FFData(109)。</p>
 * <p><b>非 B108 / 魔数不对的包直接丢弃，不产生任何落盘记录。</b></p>
 */
public class K187B108Parser {

    private static final Logger log = LoggerFactory.getLogger(K187B108Parser.class);

    public static final int HEAD1_LEN = 34;
    public static final int HEAD3_LEN = 48;
    public static final int FF_HEAD_LEN = 80;
    public static final int FF_DATA_LEN = 109;
    public static final int MAGIC_HEAD = 0x7e7e7e7e;
    /** 定频脉冲描述字信息类别号 */
    public static final int INFO_TYPE_B108 = 0xB108;

    public static final class ParseStats {
        public long packets;
        public long b108Packets;
        public long records;
        public long skippedDoa;
        public long skippedOtherType;
        public long skippedBadMagic;
        public long skippedBadLength;
    }

    private final ParseStats stats = new ParseStats();

    public ParseStats getStats() { return stats; }

    /** 是否为应保存的 B108 包（仅看 Head1，用于快速过滤）。 */
    public static boolean isB108Packet(byte[] packet) {
        if (packet == null || packet.length < HEAD1_LEN) {
            return false;
        }
        LittleEndian le = new LittleEndian(packet, 0, HEAD1_LEN);
        if (le.readU32() != MAGIC_HEAD) {
            return false;
        }
        le.readU32(); // frameIndex
        int packetLength = le.readU32();
        if (packetLength < HEAD1_LEN || packetLength > packet.length) {
            return false;
        }
        le.position(le.position() + 16); // skip SYSTEMTIME
        int infoType = le.readU16() & 0xffff;
        return infoType == INFO_TYPE_B108;
    }

    /**
     * 解析完整一包。仅当信息类别为 B108 时返回记录；否则返回空列表（调用方不得落盘）。
     */
    public List<PdwRecord> parsePacket(byte[] packet) {
        List<PdwRecord> out = new ArrayList<>();
        if (packet == null || packet.length < HEAD1_LEN + HEAD3_LEN) {
            return out;
        }
        LittleEndian le = new LittleEndian(packet);
        int magic = le.readU32();
        if (magic != MAGIC_HEAD) {
            stats.skippedBadMagic++;
            return out;
        }
        le.readU32(); // frameIndex
        int packetLength = le.readU32();
        if (packetLength <= 0 || packetLength > packet.length) {
            stats.skippedBadLength++;
            return out;
        }
        LocalDateTime headTime = readSystemTime(le);
        int infoType = le.readU16() & 0xffff;
        le.readU32(); // cmdInfoLength

        stats.packets++;
        // 只接收 B108：标识不对则整包丢弃，不保存
        if (infoType != INFO_TYPE_B108) {
            stats.skippedOtherType++;
            if (log.isDebugEnabled()) {
                log.debug("丢弃非 B108 包 infoType=0x{}",
                        Integer.toHexString(infoType).toUpperCase(Locale.ENGLISH));
            }
            return out;
        }

        // Head3（B108 才继续解析）
        if (le.remaining() < HEAD3_LEN) {
            return out;
        }
        le.position(le.position() + HEAD3_LEN);

        stats.b108Packets++;
        if (le.remaining() < FF_HEAD_LEN) {
            return out;
        }

        le.readU32(); // infoLength
        le.readU64(); // taskId
        le.readU64(); // sFreq
        le.readU64(); // dK
        LocalDateTime ffTime = readSystemTime(le);
        le.readU64(); // fpga
        int longitude = le.readU32();
        int latitude = le.readU32();
        le.readU32(); // height
        le.readU32(); // pitch
        le.readU32(); // roll
        le.readU16(); // speed
        int course = le.readU32();
        int infoNum = le.readU16() & 0xffff;

        LocalDateTime baseTime = ffTime != null ? ffTime : headTime;
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < infoNum; i++) {
            if (le.remaining() < FF_DATA_LEN) {
                break;
            }
            int qTsc = le.readU32();
            long pLzx = le.readU64();
            int zLsj = le.readU32();
            short gMdk = le.readU16();
            short fD = le.readU16();
            le.readU16(); // fWgs
            short[] fw = le.readU16Array(28);
            le.readBytes(28); // xhfd arr
            le.readU8();
            le.readU8();
            le.readU8();

            List<Short> fwList = new ArrayList<>(28);
            for (int j = 0; j < 28; j++) {
                fwList.add(fw[j]);
            }
            DoaHistChiefZhang.Result doa = DoaHistChiefZhang.fuse(fwList, pLzx / 1000L, 21);
            if (doa.code != 0 || doa.doaMean == 4000) {
                stats.skippedDoa++;
                continue;
            }
            short xhfw01 = DoaHistChiefZhang.toTrueAzimuth(doa.doaMean, course);
            LocalDateTime zcsj = baseTime;
            if (zcsj != null) {
                // 与原实现一致：+8 小时时区校正 + 起跳时戳（10µs）
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

            String key = rec.getFreqMhz() + "|" + rec.getAzimuthDeg() + "|" + rec.getDetectTime();
            if (unique.add(key)) {
                out.add(rec);
                stats.records++;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("B108 packet records={}, infoNum={}", out.size(), infoNum);
        }
        return out;
    }

    private static LocalDateTime readSystemTime(LittleEndian le) {
        int year = le.readU16() & 0xffff;
        int month = le.readU16() & 0xffff;
        le.readU16(); // dayOfWeek
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
