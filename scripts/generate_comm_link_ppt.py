# -*- coding: utf-8 -*-
"""Generate D01-D06 communication link analysis PowerPoint."""

from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches, Pt

OUT = Path(__file__).resolve().parents[1] / "docs" / "pdwfx-comm-link-d01-d06.pptx"

BLUE = RGBColor(0x1D, 0x4E, 0xD8)
DARK = RGBColor(0x1F, 0x29, 0x37)
GRAY = RGBColor(0x6B, 0x72, 0x80)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
ACCENT = RGBColor(0x0E, 0x74, 0x90)


def set_slide_bg(slide, rgb=RGBColor(0xF8, 0xFA, 0xFC)):
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = rgb


def add_title_bar(slide, title, subtitle=None):
    bar = slide.shapes.add_shape(1, Inches(0), Inches(0), Inches(10), Inches(1.05))
    bar.fill.solid()
    bar.fill.fore_color.rgb = BLUE
    bar.line.fill.background()
    tf = bar.text_frame
    tf.text = title
    p = tf.paragraphs[0]
    p.font.size = Pt(26)
    p.font.bold = True
    p.font.color.rgb = WHITE
    if subtitle:
        box = slide.shapes.add_textbox(Inches(0.55), Inches(1.15), Inches(9), Inches(0.45))
        p2 = box.text_frame.paragraphs[0]
        p2.text = subtitle
        p2.font.size = Pt(13)
        p2.font.color.rgb = GRAY


def add_bullets(slide, items, left=0.55, top=1.65, width=8.9, height=5.4, font_size=17):
    box = slide.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))
    tf = box.text_frame
    tf.word_wrap = True
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        if isinstance(item, tuple):
            text, level = item
            p.text = text
            p.level = level
        else:
            p.text = item
            p.level = 0
        p.font.size = Pt(font_size if p.level == 0 else font_size - 2)
        p.font.color.rgb = DARK
        p.space_after = Pt(6)


def content_slide(prs, title, bullets, subtitle=None, font_size=17):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_slide_bg(slide)
    add_title_bar(slide, title, subtitle)
    add_bullets(slide, bullets, font_size=font_size)


def two_col_slide(prs, title, subtitle, left_title, left_items, right_title, right_items):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_slide_bg(slide)
    add_title_bar(slide, title, subtitle)
    for t, items, x in [(left_title, left_items, 0.55), (right_title, right_items, 5.1)]:
        h = slide.shapes.add_textbox(Inches(x), Inches(1.5), Inches(4.3), Inches(0.35))
        p = h.text_frame.paragraphs[0]
        p.text = t
        p.font.bold = True
        p.font.size = Pt(15)
        p.font.color.rgb = ACCENT
        add_bullets(slide, items, left=x, top=1.85, width=4.3, height=5.0, font_size=14)


def build():
    prs = Presentation()
    prs.slide_width = Inches(10)
    prs.slide_height = Inches(7.5)

    # Cover
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_slide_bg(slide, BLUE)
    t = slide.shapes.add_textbox(Inches(0.7), Inches(2.0), Inches(8.6), Inches(1.0))
    p = t.text_frame.paragraphs[0]
    p.text = "通信链波道研判 D01～D06"
    p.font.size = Pt(36)
    p.font.bold = True
    p.font.color.rgb = WHITE
    s = slide.shapes.add_textbox(Inches(0.7), Inches(3.1), Inches(8.6), Inches(1.2))
    p2 = s.text_frame.paragraphs[0]
    p2.text = "CommunicationLinkAnalysisService\n通信侦获信号分析系统 · pdwfx"
    p2.font.size = Pt(18)
    p2.font.color.rgb = RGBColor(0xBF, 0xDB, 0xFE)

    # Agenda
    content_slide(
        prs,
        "内容提纲",
        [
            "一、各波道代表什么（D01～D06 + 不明）",
            "二、怎么判断出来（总体方法与流程）",
            "三、各波道判定要点（硬条件 + 加分项）",
            "四、与平台类型研判的关系",
            "五、快速对照表（分享用）",
        ],
    )

    # ========== 一、各波道代表什么 ==========
    content_slide(
        prs,
        "一、各波道代表什么",
        [
            "D01～D06 是单网分析后的通信链波道编码（commLinkChannel）",
            "输出字段：commLinkChannel / commLinkChannelLabel / commLinkReason / commLinkEvidence",
            "",
            "D01 — 地空数传指挥引导：地面站 + 小飞机，主从引导",
            "D02 — 异频数传指挥引导：仅小飞机，异频，长机主/僚机从",
            "D03 — 预警机态势广播：仅预警机单独持续发信",
            "D04 — 地空数传指挥：预警机 + 地面站，双驻留轮流发信",
            "D05 — 空空数传指挥引导：预警机询问 + 战机应答，无地面",
            "D06 — 跨区数传指挥协同：地面站 + 小飞机，弱主从、跨区协同",
            "UNKNOWN — 不明：六类得分均不足阈值 0.40",
        ],
        subtitle="网络级通信链结论",
        font_size=16,
    )

    content_slide(
        prs,
        "一、平台组合速览",
        [
            ("D01：地 + 机（无预警）", 0),
            ("D02：仅机（无地、无预警）", 0),
            ("D03：仅预警机", 0),
            ("D04：预警机 + 地面站", 0),
            ("D05：预警机 + 战机（无地面）", 0),
            ("D06：地 + 机（无预警，与 D01 区分主从/形态）", 0),
            "",
            "D01 与 D06 平台类似，但 D06 强调跨区协同、主从较弱",
            "D01 强调主从引导，且不能与 D04「双驻留」形态混淆",
        ],
        subtitle="典型参与平台",
        font_size=16,
    )

    # ========== 二、怎么判断出来 ==========
    content_slide(
        prs,
        "二、总体判断方法",
        [
            "时机：在平台类型、主从角色、通信模式计算完成之后",
            "方法：规则打分 + 竞争择优（非机器学习）",
            "实现类：CommunicationLinkAnalysisService.detectChannel()",
            "",
            "对 D01～D06 分别计算 score，不满足硬条件则 0 分",
            "取得分最高且 > 阈值 0.40 的波道为最终结论",
            "选定后按波道规则二次修正各目标平台类型（refineTargetTypes）",
        ],
        subtitle="规则引擎",
    )

    content_slide(
        prs,
        "二、输入特征",
        [
            "平台组合：地面站 / 预警机 / 小飞机 数量（GROUND / AWACS / AIR）",
            "主从结构：MASTER / SLAVE 数量，各目标发射时间占比",
            "通信模式：SAME_FREQ（定频）/ MULTI_FREQ（异频）/ HOPPING（跳频）",
            "节奏指标：burst 平均驻留(ms)、估计 PRI(ms)、占空比(%)、网级占空比",
            "CSV 先验（可选）：modulateDimension / modulateStyle 含 D01～D06 时作 dataHint，对应波道 +0.15 分",
        ],
        subtitle="从 TargetView + 原始信号汇总",
        font_size=16,
    )

    content_slide(
        prs,
        "二、判断流程",
        [
            "1. 统计平台组合、主从、驻留列表、是否预警机单发、是否 D04 双驻留形态",
            "2. 分别计算 scoreD01 … scoreD06",
            "3. 特殊：若「仅地面+预警机」且 D04≥0.32，优先倾向 D04",
            "4. 按顺序比较得分，取最高且 > 0.40 的波道",
            "5. 若仍不明且 CSV 有 dataHint，用提示波道重算",
            "6. refineTargetTypes：按选定波道修正各目标 GROUND/AWACS/AIR",
            "7. 输出 reason + evidence（含各波道得分明细，便于复核）",
        ],
        subtitle="detectChannel 步骤",
        font_size=16,
    )

    # ========== 三、各波道判定要点 ==========
    content_slide(
        prs,
        "三、D01 地空数传指挥引导",
        [
            "【硬条件】必须有地面站 + 小飞机；不能有预警机；不能是 D04 双驻留形态",
            "【加分】CSV 标注 D01 (+0.15)；有主站/从站；非异频模式；地、机各≥1",
            "【结论】D01 地空数传指挥引导网 / 地空数传指挥引导网",
            "【修正】非预警机的非地面目标 → 强制为 AIR（小飞机）",
        ],
        font_size=16,
    )

    content_slide(
        prs,
        "三、D02 异频数传指挥引导",
        [
            "【硬条件】仅小飞机，无地面站、无预警机",
            "【加分】MULTI_FREQ (+0.35，核心)；1主+≥2从；≥2飞机从机；目标≥3",
            "【结论】D02 异频数传指挥引导网",
            "【修正】非地面、非预警目标 → AIR",
        ],
        font_size=16,
    )

    content_slide(
        prs,
        "三、D03 预警机态势广播",
        [
            "【硬条件】预警机单发：无地面/战机主体；非MASTER发射占比≤15%；明显发射者≤1",
            "【参考区间】驻留 20～32ms | PRI 80～1900ms | 占空比 3%～11%",
            "【加分】定频/跳频略加分；主发射者指标命中各区间",
            "【结论】D03 预警机态势广播",
            "【修正】全部目标 → AWACS",
        ],
        font_size=16,
    )

    content_slide(
        prs,
        "三、D04 地空数传指挥",
        [
            "【硬条件】仅预警机 + 地面站（少量误分飞机且发射占比<15% 可容忍）",
            "【否决】存在明显小飞机主体 → D04 不适用",
            "【双驻留形态】短驻留 12～28ms（≈预警机）+ 长驻留 48～78ms（≈地面站）",
            "【加分】网占空 28%～38%；地占空~25%；预警占空 3%～15%；PRI 206～320ms",
            "【修正】短驻留→AWACS，长驻留→GROUND",
        ],
        font_size=15,
    )

    content_slide(
        prs,
        "三、D05 空空数传指挥引导",
        [
            "【硬条件】无地面站；必须预警机 + 战斗机同时存在",
            "【加分】1主+≥2从；驻留 15～42ms；网占空 6%～11%",
            "【MASTER/预警机】PRI 60～802ms，占空 4%～8%",
            "【SLAVE/战机】PRI 1.2～10.1s，占空 ≤约1%",
            "【降权】异频模式总分 ×0.5",
            "【修正】MASTER→AWACS（询问），SLAVE→AIR（应答）",
        ],
        font_size=15,
    )

    content_slide(
        prs,
        "三、D06 跨区数传指挥协同",
        [
            "【硬条件】地面站 + 小飞机，无预警机；不能是 D04 双驻留形态",
            "【加分】地、机各≥1；非异频略加分；主从较弱或从站较少",
            "【与 D01 区分】D01 强调强主从引导；D06 强调跨区协同、弱主从",
            "【结论】D06 跨区数传指挥协同网",
            "【修正】同 D01，非预警非地面 → AIR",
        ],
        font_size=16,
    )

    # ========== 四、与平台类型研判的关系 ==========
    content_slide(
        prs,
        "四、与平台类型研判的关系",
        [
            "通信链研判在平台类型、主从、占空比/PRI 计算之后执行",
            "平台类型主判据（前置）：占空比 — 地≥25% / 预>4% / 机<4%；辅以运动椭圆、PRI",
            "",
            "通信链结论会反向修正（refineTargetTypes）各目标标签：",
            "D01/D06 → 非预警非地面强制 AIR",
            "D02 → 非地非预 → AIR",
            "D03 → 全部 AWACS",
            "D04 → 按驻留长短 → AWACS 或 GROUND",
            "D05 → MASTER→AWACS，SLAVE→AIR",
            "",
            "因此 D01～D06 既是网络级结论，也约束目标级平台标注",
        ],
        subtitle="前置依赖 + 反向修正",
        font_size=15,
    )

    two_col_slide(
        prs,
        "四、修正逻辑对照",
        "选定波道后的 refineTargetTypes",
        "波道",
        ["D01 / D06", "D02", "D03", "D04", "D05"],
        "目标类型修正",
        [
            "非预警 → AIR（小飞机）",
            "非地、非预 → AIR",
            "全部 → AWACS",
            "短驻留→AWACS，长驻留→GROUND",
            "主→AWACS，从→AIR",
        ],
    )

    # ========== 五、快速对照表 ==========
    content_slide(
        prs,
        "五、快速对照表",
        [
            "D01 = 地 + 机，主从引导，无预警，非 D04 形态",
            "D02 = 仅机，异频 MULTI_FREQ，长机主僚机从",
            "D03 = 仅预警机单发，驻留/PRI/占空符合广播特征",
            "D04 = 预警机 + 地面，短/长双驻留轮流发信",
            "D05 = 预警机 + 战机，无地面，主问从答、PRI/占空分角色",
            "D06 = 地 + 机，无预警，跨区协同，弱主从，非 D04 形态",
            "",
            "判不出 → UNKNOWN（不明）",
            "commLinkEvidence 含各波道得分与选用理由，供人工复核",
        ],
        subtitle="分享速记",
        font_size=16,
    )

    content_slide(
        prs,
        "五、输出字段与排错",
        [
            "commLinkChannel      — 波道编码 D01～D06 / UNKNOWN",
            "commLinkChannelLabel — 中文名称，如「D04 地空数传指挥」",
            "commLinkReason       — 可读结论摘要",
            "commLinkEvidence     — 调试数组：平台组合、各波道得分、D04否决原因、最终选用",
            "",
            "排错建议：先看 evidence 中 D01～D06 得分，再核对平台组合与驻留/PRI 是否命中参考区间",
        ],
        subtitle="NetworkView 字段",
        font_size=16,
    )

    # Q&A
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_slide_bg(slide, BLUE)
    t = slide.shapes.add_textbox(Inches(2.2), Inches(2.8), Inches(5.6), Inches(1.0))
    p = t.text_frame.paragraphs[0]
    p.text = "Q & A"
    p.alignment = PP_ALIGN.CENTER
    p.font.size = Pt(44)
    p.font.bold = True
    p.font.color.rgb = WHITE

    OUT.parent.mkdir(parents=True, exist_ok=True)
    prs.save(str(OUT))
    print(f"Saved: {OUT}")


if __name__ == "__main__":
    build()
