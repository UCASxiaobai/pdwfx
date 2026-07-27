package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.model.TargetView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通信链属性研判 D01～D06 + 不明。
 * 各波道约束平台组合、驻留/PRI/占空比参考区间（todo 166-174）。
 */
@Component
public class CommunicationLinkAnalysisService {

    public static final String CHANNEL_D01 = "D01";
    public static final String CHANNEL_D02 = "D02";
    public static final String CHANNEL_D03 = "D03";
    public static final String CHANNEL_D04 = "D04";
    public static final String CHANNEL_D05 = "D05";
    public static final String CHANNEL_D06 = "D06";
    public static final String CHANNEL_UNKNOWN = "UNKNOWN";
    public static final String LABEL_UNKNOWN = "不明";

    public static final double DUTY_AWACS_MIN_PCT = 4.0;
    public static final double DUTY_GROUND_MIN_PCT = 25.0;
    public static final double AWACS_PRI_MAX_MS = 2000.0;

    private static final double SCORE_THRESHOLD = 0.40;

    // D03 定频 VU 参考
    private static final double D03_DWELL_LO = 20.0;
    private static final double D03_DWELL_HI = 32.0;
    private static final double D03_PRI_LO_MS = 80.0;
    private static final double D03_PRI_HI_MS = 1900.0;
    private static final double D03_DUTY_LO = 3.0;
    private static final double D03_DUTY_HI = 11.0;

    // D04
    private static final double D04_AWACS_DWELL_LO = 12.0;
    private static final double D04_AWACS_DWELL_HI = 28.0;
    private static final double D04_GROUND_DWELL_LO = 48.0;
    private static final double D04_GROUND_DWELL_HI = 78.0;
    private static final double D04_PRI_LO_MS = 206.0;
    private static final double D04_PRI_HI_MS = 320.0;
    private static final double D04_GROUND_DUTY_REF = 25.0;
    private static final double D04_AWACS_DUTY_REF = 7.35;
    private static final double D04_NET_DUTY_LO = 28.0;
    private static final double D04_NET_DUTY_HI = 38.0;

    // D05
    private static final double D05_DWELL_LO = 15.0;
    private static final double D05_DWELL_HI = 42.0;
    private static final double D05_AWACS_PRI_LO_MS = 60.0;
    private static final double D05_AWACS_PRI_HI_MS = 802.0;
    private static final double D05_FIGHTER_PRI_LO_MS = 1200.0;
    private static final double D05_FIGHTER_PRI_HI_MS = 10110.0;
    private static final double D05_AWACS_DUTY_LO = 4.0;
    private static final double D05_AWACS_DUTY_HI = 8.0;
    private static final double D05_FIGHTER_DUTY_HI = 1.0;
    private static final double D05_NET_DUTY_LO = 6.0;
    private static final double D05_NET_DUTY_HI = 11.0;

    public LinkAssessment analyze(NetworkView view, List<DetectSignal> networkSignals) {
        LinkAssessment link = detectChannel(view, networkSignals);
        view.setCommLinkChannel(link.channel);
        view.setCommLinkChannelLabel(link.channelLabel);
        view.setCommLinkReason(link.reason);
        view.setCommLinkEvidence(link.evidence);
        refineTargetTypes(view, link);
        if (link.networkTypeLabel != null) {
            view.setNetworkType(link.networkTypeLabel);
        }
        return link;
    }

    public boolean qualifiesSecondaryAwacs(TargetView target) {
        double duty = target.getAvgDutyCycle();
        if (duty < DUTY_AWACS_MIN_PCT || duty >= DUTY_GROUND_MIN_PCT) return false;
        Double pri = target.getEstimatedPriMs();
        return pri != null && pri > 0 && pri < AWACS_PRI_MAX_MS;
    }

    public String secondaryAwacsNote(TargetView target) {
        return String.format("次级：占空比%.1f%%≥%.0f%%且PRI%.0fms<%.0fs → 预警机",
                target.getAvgDutyCycle(), DUTY_AWACS_MIN_PCT,
                target.getEstimatedPriMs(), AWACS_PRI_MAX_MS / 1000.0);
    }

    private void refineTargetTypes(NetworkView view, LinkAssessment link) {
        for (TargetView t : view.getTargets()) {
            double dwell = t.getBurstDurationMeanMs();
            String refined = null;
            String note = null;
            switch (link.channel) {
                case CHANNEL_D01:
                case CHANNEL_D06:
                    if ("GROUND".equals(t.getTargetType())) {
                        note = "波道" + link.channel + "：仅允许地面站+小飞机(非预警机)";
                    } else if (!"AWACS".equals(t.getTargetType())) {
                        refined = "AIR";
                        note = "波道" + link.channel + "：小飞机(非预警机)";
                    }
                    break;
                case CHANNEL_D02:
                    if (!"GROUND".equals(t.getTargetType()) && !"AWACS".equals(t.getTargetType())) {
                        refined = "AIR";
                        note = "波道D02：异频空空，仅小飞机";
                    }
                    break;
                case CHANNEL_D03:
                    refined = "AWACS";
                    note = String.format("波道D03：仅预警机发射；%s；驻留%.1fms",
                            link.subModeLabel, dwell);
                    break;
                case CHANNEL_D04:
                    if (inBand(dwell, D04_AWACS_DWELL_LO, D04_AWACS_DWELL_HI)) {
                        refined = "AWACS";
                        note = String.format("波道D04：驻留%.1fms≈预警机(~18.5ms)", dwell);
                    } else if (inBand(dwell, D04_GROUND_DWELL_LO, D04_GROUND_DWELL_HI)) {
                        refined = "GROUND";
                        note = String.format("波道D04：驻留%.1fms≈地面站(~63.4ms)", dwell);
                    }
                    break;
                case CHANNEL_D05:
                    if ("MASTER".equals(t.getRole())) {
                        refined = "AWACS";
                        note = String.format("波道D05：预警机询问；%s", link.subModeLabel);
                    } else if ("SLAVE".equals(t.getRole())) {
                        refined = "AIR";
                        note = String.format("波道D05：作战飞机应答；驻留%.1fms", dwell);
                    }
                    break;
                default:
                    break;
            }
            if (refined != null && !refined.equals(t.getTargetType())) {
                t.setTargetType(refined);
                t.setTargetTypeReason(appendReason(t.getTargetTypeReason(), note));
            } else if (note != null) {
                t.setTargetTypeReason(appendReason(t.getTargetTypeReason(), note));
            }
        }
    }

    private LinkAssessment detectChannel(NetworkView view, List<DetectSignal> signals) {
        String dataHint = dominantModulateDimension(signals);
        List<TargetView> targets = view.getTargets();
        PlatformMix mix = PlatformMix.of(targets);
        List<Double> dwells = dwellMeans(targets);
        int n = targets.size();
        long masters = roleCount(targets, "MASTER");
        long slaves = roleCount(targets, "SLAVE");
        long airSlaves = targets.stream()
                .filter(t -> "SLAVE".equals(t.getRole()) && isSmallAircraft(t))
                .count();
        boolean awacsSingle = isAwacsSingleTransmit(targets, masters, slaves);
        boolean d04Pattern = isD04DwellPattern(dwells, mix);
        String subMode = subModeLabel(view.getCommMode());

        double s01 = scoreD01(dataHint, view, mix, dwells, d04Pattern);
        double s02 = scoreD02(dataHint, view, mix, masters, slaves, airSlaves);
        double s03 = scoreD03(dataHint, view, mix, targets, dwells, awacsSingle, subMode);
        double s04 = scoreD04(dataHint, view, mix, dwells, d04Pattern);
        double s05 = scoreD05(dataHint, view, mix, targets, masters, slaves, airSlaves, dwells, subMode);
        double s06 = scoreD06(dataHint, view, mix, dwells, d04Pattern);
        boolean groundAwacsOnly = isGroundAwacsOnlyPlatform(targets, mix);

        List<String> evidence = new ArrayList<>();
        evidence.add(String.format("平台组合[地%d 预%d 机%d] 预警机单发=%s D04双驻留形态=%s 模式=%s 标注=%s",
                mix.countGround, mix.countAwacs, mix.countAir, awacsSingle ? "是" : "否",
                d04Pattern ? "是" : "否", view.getCommMode(), hintText(dataHint)));
        evidence.add(String.format("仅地面+预警机(无战机主体)=%s", groundAwacsOnly ? "是" : "否"));
        appendScoreLine(evidence, "D01", scoreD01(dataHint, view, mix, dwells, d04Pattern));
        appendScoreLine(evidence, "D02", scoreD02(dataHint, view, mix, masters, slaves, airSlaves));
        appendScoreLine(evidence, "D03", s03);
        appendScoreLine(evidence, "D04", s04);
        appendScoreLine(evidence, "D05", s05);
        appendScoreLine(evidence, "D06", scoreD06(dataHint, view, mix, dwells, d04Pattern));
        if (!scoreD04VetoReason(targets, mix).isEmpty()) {
            evidence.add("D04否决: " + scoreD04VetoReason(targets, mix));
        }

        String channel = CHANNEL_UNKNOWN;
        double best = SCORE_THRESHOLD;
        if (groundAwacsOnly && s04 >= 0.32) {
            best = s04;
            channel = CHANNEL_D04;
        }
        if (s03 >= best) { best = s03; channel = CHANNEL_D03; }
        if (s04 > best) { best = s04; channel = CHANNEL_D04; }
        if (s05 > best) { best = s05; channel = CHANNEL_D05; }
        if (s02 > best) { best = s02; channel = CHANNEL_D02; }
        if (s01 > best) { best = s01; channel = CHANNEL_D01; }
        if (s06 > best) { best = s06; channel = CHANNEL_D06; }

        if (CHANNEL_UNKNOWN.equals(channel) && dataHint != null) {
            double hinted = hintScore(dataHint, view, mix, targets, dwells, awacsSingle, d04Pattern,
                    masters, slaves, airSlaves, subMode);
            if (hinted >= SCORE_THRESHOLD) {
                channel = dataHint;
                best = hinted;
            }
        }

        evidence.add(String.format("→ 选用 %s 得分 %.2f (阈值 %.2f)", channel, best, SCORE_THRESHOLD));
        LinkAssessment a = buildAssessment(channel, best, dataHint, n, masters, slaves, dwells,
                awacsSingle, view, mix, subMode);
        a.evidence = evidence;
        return a;
    }

    private static void appendScoreLine(List<String> evidence, String code, double score) {
        evidence.add(String.format("%s 得分 %.2f%s", code, score, score >= SCORE_THRESHOLD ? " ✓" : ""));
    }

    /** D03：仅预警机发射，预警机单发 */
    private boolean isAwacsSingleTransmit(List<TargetView> targets, long masters, long slaves) {
        if (targets.isEmpty()) return false;
        PlatformMix mix = PlatformMix.of(targets);
        if (mix.hasGround) return false;
        if (mix.hasAir && mix.countAwacs == 0) return false;

        double nonMasterShare = targets.stream()
                .filter(t -> !"MASTER".equals(t.getRole()))
                .mapToDouble(TargetView::getEmissionSharePct)
                .sum();
        if (nonMasterShare > 15.0) return false;

        long emitters = targets.stream().filter(t -> t.getEmissionSharePct() >= 10.0).count();
        if (emitters >= 2) return false;

        if (targets.size() == 1) {
            return "AWACS".equals(targets.get(0).getTargetType()) || mix.countAwacs >= 1;
        }
        return masters >= 1 && slaves == 0 && mix.countAwacs >= 1 && !mix.hasAir;
    }

    private boolean isD04DwellPattern(List<Double> dwells, PlatformMix mix) {
        boolean shortB = dwells.stream().anyMatch(d -> inBand(d, D04_AWACS_DWELL_LO, D04_AWACS_DWELL_HI));
        boolean longB = dwells.stream().anyMatch(d -> inBand(d, D04_GROUND_DWELL_LO, D04_GROUND_DWELL_HI));
        return shortB && longB && mix.hasGround && mix.hasAwacs;
    }

    private double scoreD01(String hint, NetworkView view, PlatformMix mix, List<Double> dwells, boolean d04) {
        if (!mix.hasGround || !mix.hasAir || mix.hasAwacs) return 0;
        if (d04) return 0;
        double s = hintBoost(hint, CHANNEL_D01, 0.35);
        if (view.getMainStationCount() > 0 && view.getSubStationCount() > 0) s += 0.2;
        if (!"MULTI_FREQ".equals(view.getCommMode())) s += 0.1;
        if (mix.countAir >= 1 && mix.countGround >= 1) s += 0.2;
        return s;
    }

    /** 仅小飞机，无预警机/地面站，异频，长机主僚机从 */
    private double scoreD02(String hint, NetworkView view, PlatformMix mix,
                          long masters, long slaves, long airSlaves) {
        if (mix.hasGround || mix.hasAwacs) return 0;
        if (!mix.hasAir && view.getTargets().size() > 0) return 0;
        double s = hintBoost(hint, CHANNEL_D02, 0.35);
        if ("MULTI_FREQ".equals(view.getCommMode())) s += 0.35;
        if (masters == 1 && slaves >= 2) s += 0.2;
        if (airSlaves >= 2) s += 0.2;
        if (view.getTargets().size() >= 3) s += 0.1;
        return s;
    }

    private double scoreD03(String hint, NetworkView view, PlatformMix mix, List<TargetView> targets,
                            List<Double> dwells, boolean awacsSingle, String subMode) {
        if (!awacsSingle || mix.hasGround || mix.hasAir) return 0;
        if (!mix.hasAwacs && targets.stream().noneMatch(t -> "AWACS".equals(t.getTargetType()))) return 0;

        TargetView emitter = primaryEmitter(targets);
        if (emitter == null) return 0;

        double s = hintBoost(hint, CHANNEL_D03, 0.3);
        if (inBand(emitter.getBurstDurationMeanMs(), D03_DWELL_LO, D03_DWELL_HI)) s += 0.25;
        if (priInBand(emitter.getEstimatedPriMs(), D03_PRI_LO_MS, D03_PRI_HI_MS)) s += 0.2;
        if (inBand(emitter.getAvgDutyCycle(), D03_DUTY_LO, D03_DUTY_HI)) s += 0.15;
        if ("HOPPING".equals(view.getCommMode()) || "SAME_FREQ".equals(view.getCommMode())) s += 0.05;
        return s;
    }

    /**
     * D04：仅预警机+地面站。允许少量误分为「飞机」的目标（占空比/PRI 接近预警机）。
     */
    private boolean isGroundAwacsOnlyPlatform(List<TargetView> targets, PlatformMix mix) {
        if (!mix.hasGround || !mix.hasAwacs) return false;
        if (mix.countAir == 0) return true;
        double airShare = targets.stream()
                .filter(t -> "AIR".equals(t.getTargetType()))
                .mapToDouble(TargetView::getEmissionSharePct)
                .sum();
        if (airShare < 15.0 && mix.countGround >= 1 && mix.countAwacs >= 1) return true;
        return targets.stream().allMatch(t ->
                "GROUND".equals(t.getTargetType()) || "AWACS".equals(t.getTargetType()));
    }

    private String scoreD04VetoReason(List<TargetView> targets, PlatformMix mix) {
        if (!mix.hasGround || !mix.hasAwacs) return "缺少地面站或预警机";
        if (!isGroundAwacsOnlyPlatform(targets, mix)) {
            return "存在小飞机主体(非仅地面+预警机)";
        }
        return "";
    }

    /** 仅预警机+地面站 */
    private double scoreD04(String hint, NetworkView view, PlatformMix mix,
                            List<Double> dwells, boolean d04Pattern) {
        List<TargetView> targets = view.getTargets();
        if (!isGroundAwacsOnlyPlatform(targets, mix)) return 0;
        double s = hintBoost(hint, CHANNEL_D04, 0.42);
        if (d04Pattern) s += 0.28;
        else {
            boolean shortB = dwells.stream().anyMatch(d -> inBand(d, D04_AWACS_DWELL_LO, D04_AWACS_DWELL_HI));
            boolean longB = dwells.stream().anyMatch(d -> inBand(d, D04_GROUND_DWELL_LO, D04_GROUND_DWELL_HI));
            if (shortB || longB) s += 0.12;
            if (mix.countGround >= 1 && mix.countAwacs >= 1) s += 0.18;
        }
        if (inBand(view.getNetworkDutyCycle(), D04_NET_DUTY_LO, D04_NET_DUTY_HI)) s += 0.1;
        boolean groundDutyOk = targets.stream()
                .filter(t -> "GROUND".equals(t.getTargetType()))
                .anyMatch(t -> t.getAvgDutyCycle() >= D04_GROUND_DUTY_REF - 10);
        boolean awacsDutyOk = targets.stream()
                .filter(t -> "AWACS".equals(t.getTargetType()) || awacsLikeTarget(t))
                .anyMatch(t -> inBand(t.getAvgDutyCycle(), 3, 15));
        if (groundDutyOk && awacsDutyOk) s += 0.12;
        if (targets.stream().anyMatch(t -> priInBand(t.getEstimatedPriMs(), D04_PRI_LO_MS, D04_PRI_HI_MS))) {
            s += 0.08;
        }
        if (view.getMainStationCount() > 0 && view.getSubStationCount() > 0) s += 0.05;
        return s;
    }

    /** 占空与 PRI 接近预警机、却被标为 AIR 的，D04 研判时视作预警机侧 */
    private static boolean awacsLikeTarget(TargetView t) {
        if (!"AIR".equals(t.getTargetType())) return false;
        double duty = t.getAvgDutyCycle();
        Double pri = t.getEstimatedPriMs();
        return duty >= DUTY_AWACS_MIN_PCT && duty < DUTY_GROUND_MIN_PCT
                && pri != null && pri >= D04_PRI_LO_MS && pri <= D04_PRI_HI_MS;
    }

    /** 预警机+作战飞机，无地面站 */
    private double scoreD05(String hint, NetworkView view, PlatformMix mix, List<TargetView> targets,
                            long masters, long slaves, long airSlaves, List<Double> dwells, String subMode) {
        if (mix.hasGround) return 0;
        if (!mix.hasAwacs || !mix.hasAir) return 0;
        double s = hintBoost(hint, CHANNEL_D05, 0.35);
        if (masters >= 1 && slaves >= 2) s += 0.15;
        if (airSlaves >= 2) s += 0.15;
        long dwellHit = dwells.stream().filter(d -> inBand(d, D05_DWELL_LO, D05_DWELL_HI)).count();
        if (dwellHit >= 2) s += 0.2;
        if (inBand(view.getNetworkDutyCycle(), D05_NET_DUTY_LO, D05_NET_DUTY_HI)) s += 0.1;

        TargetView master = targets.stream().filter(t -> "MASTER".equals(t.getRole())).findFirst().orElse(null);
        if (master != null) {
            if (priInBand(master.getEstimatedPriMs(), D05_AWACS_PRI_LO_MS, D05_AWACS_PRI_HI_MS)) s += 0.1;
            if (inBand(master.getAvgDutyCycle(), D05_AWACS_DUTY_LO, D05_AWACS_DUTY_HI)) s += 0.1;
        }
        boolean fighterPri = targets.stream()
                .filter(t -> "SLAVE".equals(t.getRole()))
                .anyMatch(t -> priInBand(t.getEstimatedPriMs(), D05_FIGHTER_PRI_LO_MS, D05_FIGHTER_PRI_HI_MS));
        boolean fighterLowDuty = targets.stream()
                .filter(t -> "SLAVE".equals(t.getRole()))
                .anyMatch(t -> t.getAvgDutyCycle() <= D05_FIGHTER_DUTY_HI + 0.5);
        if (fighterPri && fighterLowDuty) s += 0.1;
        if ("MULTI_FREQ".equals(view.getCommMode())) s *= 0.5;
        return s;
    }

    /** 跨区协同：地面站+小飞机，无预警机，非D04 */
    private double scoreD06(String hint, NetworkView view, PlatformMix mix, List<Double> dwells, boolean d04) {
        if (!mix.hasGround || !mix.hasAir || mix.hasAwacs) return 0;
        if (d04) return 0;
        double s = hintBoost(hint, CHANNEL_D06, 0.35);
        if (mix.countGround >= 1 && mix.countAir >= 1) s += 0.2;
        if (!"MULTI_FREQ".equals(view.getCommMode())) s += 0.1;
        if (view.getMainStationCount() == 0 && view.getSubStationCount() == 0) s += 0.1;
        else if (view.getSubStationCount() <= 1) s += 0.05;
        return s;
    }

    private double hintScore(String hint, NetworkView view, PlatformMix mix, List<TargetView> targets,
                           List<Double> dwells, boolean awacsSingle, boolean d04,
                           long masters, long slaves, long airSlaves, String subMode) {
        switch (hint) {
            case CHANNEL_D01: return scoreD01(hint, view, mix, dwells, d04);
            case CHANNEL_D02: return scoreD02(hint, view, mix, masters, slaves, airSlaves);
            case CHANNEL_D03: return scoreD03(hint, view, mix, targets, dwells, awacsSingle, subMode);
            case CHANNEL_D04: return scoreD04(hint, view, mix, dwells, d04);
            case CHANNEL_D05: return scoreD05(hint, view, mix, targets, masters, slaves, airSlaves, dwells, subMode);
            case CHANNEL_D06: return scoreD06(hint, view, mix, dwells, d04);
            default: return 0;
        }
    }

    private LinkAssessment buildAssessment(String channel, double score, String dataHint,
                                           int n, long masters, long slaves, List<Double> dwells,
                                           boolean awacsSingle, NetworkView view,
                                           PlatformMix mix, String subMode) {
        LinkAssessment a = new LinkAssessment();
        a.channel = channel;
        a.confidence = Math.min(1.0, score);
        a.subModeLabel = subMode;
        TargetView emitter = primaryEmitter(view.getTargets());

        switch (channel) {
            case CHANNEL_D01:
                a.channelLabel = "D01 地空数传指挥引导";
                a.networkTypeLabel = "地空数传指挥引导网";
                a.reason = String.format(
                        "D01：仅地面站+小飞机(非预警机)，主从引导；模式%s；目标%d(地%d机%d)；非D04双驻留；%s",
                        view.getCommMode(), n, mix.countGround, mix.countAir, platformNote(mix));
                break;
            case CHANNEL_D02:
                a.channelLabel = "D02 异频数传指挥引导";
                a.networkTypeLabel = "异频数传指挥引导网";
                a.reason = String.format(
                        "D02：仅小飞机、无预警机/地面站；MULTI_FREQ；长机主/僚机从；目标%d主%d从%d",
                        n, masters, slaves);
                break;
            case CHANNEL_D03:
                a.channelLabel = "D03 预警机态势广播";
                a.networkTypeLabel = "预警机态势广播";
                a.reason = String.format(
                        "D03：%s；仅预警机单发；驻留24～28ms；PRI 80ms～1.9s；占空比3.3%%～10.8%%；观测驻留%s PRI%s 占空比%s",
                        subMode, dwellListText(dwells),
                        emitter != null ? formatPri(emitter.getEstimatedPriMs()) : "-",
                        emitter != null ? String.format("%.1f%%", emitter.getAvgDutyCycle()) : "-");
                break;
            case CHANNEL_D04:
                a.channelLabel = "D04 地空数传指挥";
                a.networkTypeLabel = "地空数传指挥";
                a.reason = String.format(
                        "D04：仅预警机+地面站轮流；短帧~18.5ms/长帧~63.4ms；地占空~25%%/预警~7.35%%/整体~32.5%%；观测%s 网占空%.1f%%",
                        dwellListText(dwells), view.getNetworkDutyCycle());
                break;
            case CHANNEL_D05:
                a.channelLabel = "D05 空空数传指挥引导";
                a.networkTypeLabel = "空空数传指挥引导";
                a.reason = String.format(
                        "D05：%s；无地面站；预警机询问/飞机应答；驻留18～39ms；预警PRI 60～802ms/战机1.2～10.1s；网占空%.1f%%",
                        subMode, view.getNetworkDutyCycle());
                break;
            case CHANNEL_D06:
                a.channelLabel = "D06 跨区数传指挥协同";
                a.networkTypeLabel = "跨区数传指挥协同网";
                a.reason = String.format(
                        "D06：仅地面站+小飞机(非预警机)，跨区协同；模式%s；目标%d(地%d机%d)；%s",
                        view.getCommMode(), n, mix.countGround, mix.countAir, platformNote(mix));
                break;
            default:
                a.channelLabel = LABEL_UNKNOWN;
                a.networkTypeLabel = LABEL_UNKNOWN;
                a.reason = String.format(
                        "未匹配D01～D06：模式%s；平台[地%d预%d机%d]；单发=%s；D04形态=%s；标注=%s",
                        view.getCommMode(), mix.countGround, mix.countAwacs, mix.countAir,
                        awacsSingle ? "是" : "否", isD04DwellPattern(dwells, mix) ? "是" : "否",
                        hintText(dataHint));
                break;
        }
        return a;
    }

    private static TargetView primaryEmitter(List<TargetView> targets) {
        return targets.stream()
                .max((a, b) -> Double.compare(a.getEmissionSharePct(), b.getEmissionSharePct()))
                .orElse(targets.isEmpty() ? null : targets.get(0));
    }

    private static boolean isSmallAircraft(TargetView t) {
        return "AIR".equals(t.getTargetType()) || (!"GROUND".equals(t.getTargetType()) && !"AWACS".equals(t.getTargetType()));
    }

    private static long roleCount(List<TargetView> targets, String role) {
        return targets.stream().filter(t -> role.equals(t.getRole())).count();
    }

    private static List<Double> dwellMeans(List<TargetView> targets) {
        return targets.stream().map(TargetView::getBurstDurationMeanMs).filter(d -> d > 0).collect(Collectors.toList());
    }

    private static double hintBoost(String hint, String channel, double base) {
        return channel.equals(hint) ? base + 0.15 : base;
    }

    private static String subModeLabel(String commMode) {
        if ("HOPPING".equals(commMode)) return "跳频";
        if ("SAME_FREQ".equals(commMode)) return "定频";
        if ("MULTI_FREQ".equals(commMode)) return "异频";
        return "模式" + (commMode != null ? commMode : "未知");
    }

    private static String platformNote(PlatformMix mix) {
        return String.format("平台[地%d预%d机%d]", mix.countGround, mix.countAwacs, mix.countAir);
    }

    private static String formatPri(Double priMs) {
        return priMs == null || priMs <= 0 ? "-" : String.format("%.0fms", priMs);
    }

    private static boolean priInBand(Double priMs, double lo, double hi) {
        return priMs != null && priMs >= lo && priMs <= hi;
    }

    static String normalizeChannelCode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (matchesCode(u, "01", CHANNEL_D01)) return CHANNEL_D01;
        if (matchesCode(u, "02", CHANNEL_D02)) return CHANNEL_D02;
        if (matchesCode(u, "03", CHANNEL_D03)) return CHANNEL_D03;
        if (matchesCode(u, "04", CHANNEL_D04)) return CHANNEL_D04;
        if (matchesCode(u, "05", CHANNEL_D05)) return CHANNEL_D05;
        if (matchesCode(u, "06", CHANNEL_D06)) return CHANNEL_D06;
        return null;
    }

    private static boolean matchesCode(String u, String num, String code) {
        return u.contains(code) || u.equals(num) || u.endsWith("-" + num) || u.matches("D0?" + num);
    }

    private static String dominantModulateDimension(List<DetectSignal> signals) {
        Map<String, Integer> counts = new HashMap<>();
        for (DetectSignal s : signals) {
            String code = normalizeChannelCode(s.getModulateDimension());
            if (code == null) code = normalizeChannelCode(s.getModulateStyle());
            if (code != null) counts.merge(code, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static boolean inBand(double v, double lo, double hi) {
        return v >= lo && v <= hi;
    }

    private static String hintText(String hint) {
        return hint != null ? hint : "无";
    }

    private static String dwellListText(List<Double> dwells) {
        if (dwells.isEmpty()) return "无";
        return dwells.stream().limit(4).map(d -> String.format("%.1fms", d)).collect(Collectors.joining(","));
    }

    private static String appendReason(String existing, String note) {
        if (existing == null || existing.isEmpty()) return note;
        if (existing.contains(note)) return existing;
        return existing + "；" + note;
    }

    static final class PlatformMix {
        final boolean hasGround;
        final boolean hasAwacs;
        final boolean hasAir;
        final int countGround;
        final int countAwacs;
        final int countAir;

        private PlatformMix(boolean hasGround, boolean hasAwacs, boolean hasAir,
                            int countGround, int countAwacs, int countAir) {
            this.hasGround = hasGround;
            this.hasAwacs = hasAwacs;
            this.hasAir = hasAir;
            this.countGround = countGround;
            this.countAwacs = countAwacs;
            this.countAir = countAir;
        }

        static PlatformMix of(List<TargetView> targets) {
            int g = 0, a = 0, f = 0;
            for (TargetView t : targets) {
                if ("GROUND".equals(t.getTargetType())) g++;
                else if ("AWACS".equals(t.getTargetType())) a++;
                else f++;
            }
            return new PlatformMix(g > 0, a > 0, f > 0, g, a, f);
        }
    }

    public static class LinkAssessment {
        public String channel = CHANNEL_UNKNOWN;
        public String channelLabel;
        public String networkTypeLabel;
        public String reason;
        public String subModeLabel;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
    }
}
