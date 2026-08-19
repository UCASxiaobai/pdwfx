/**

 * 导出 Word 可打开的 .doc（HTML 封装，无需 docx npm 包）。

 */



import { formatFreq, targetTypeLabel } from "./sceneFilters.js";

function formatLocate(row) {
  const lon = row.targetLocateLon;
  const lat = row.targetLocateLat;
  if (lon == null || lat == null) return "—";
  const a = Number(lon);
  const b = Number(lat);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return "—";
  return `${a.toFixed(4)}, ${b.toFixed(4)}`;
}

function formatDetectTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}



function esc(s) {

  return String(s ?? "—")

    .replace(/&/g, "&amp;")

    .replace(/</g, "&lt;")

    .replace(/>/g, "&gt;")

    .replace(/"/g, "&quot;");

}



function buildTable(headers, rows) {

  const head = headers.map((h) => `<th>${esc(h)}</th>`).join("");

  const body = rows

    .map((cells) => `<tr>${cells.map((c) => `<td>${esc(c)}</td>`).join("")}</tr>`)

    .join("");

  return `<table border="1" cellpadding="4" cellspacing="0" style="border-collapse:collapse;width:100%;font-size:10pt;">`

    + `<thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;

}



function buildMergedDetailTable(formattedRows) {

  const headers = [

    "场景", "起止时间", "网络", "网络频率",

    "波道", "目标", "目标类型", "定位", "定位方式", "侦获起", "侦获止", "侦获次数", "角色", "置信度", "流量%"

  ];

  const head = headers.map((h) => `<th style="background:#f3f4f6;">${esc(h)}</th>`).join("");

  const body = (formattedRows || [])

    .map((row) => {

      const cells = [];

      if (row.showScene) {

        cells.push(`<td rowspan="${row.spanScene}" style="text-align:center;vertical-align:middle;">${esc(row.sceneLabel)}</td>`);

      }

      if (row.showTime) {

        cells.push(
          `<td rowspan="${row.spanTime}" style="text-align:center;vertical-align:middle;line-height:1.35;">`
          + `<div>${esc(row.timeStartLabel)}</div>`
          + `<div>~</div>`
          + `<div>${esc(row.timeEndLabel)}</div>`
          + `</td>`
        );

      }

      const conf = Number(row.confidence);

      const share = Number(row.emissionSharePct);

      if (row.showNetwork) {
        cells.push(
          `<td rowspan="${row.spanNetwork}" style="text-align:center;vertical-align:middle;">${esc(row.networkId)}</td>`
        );
      }
      if (row.showNetworkFreq) {
        cells.push(
          `<td rowspan="${row.spanNetworkFreq}" style="text-align:right;vertical-align:middle;">${esc(formatFreq(row.networkFreqMhz))}</td>`
        );
      }
      if (row.showChannel) {
        cells.push(
          `<td rowspan="${row.spanChannel}" style="text-align:center;vertical-align:middle;">${esc(row.commLinkChannelLabel || row.commLinkChannel || "—")}</td>`
        );
      }

      cells.push(

        `<td style="text-align:center;">${esc(row.targetId)}</td>`,

        `<td style="text-align:center;">${esc(targetTypeLabel(row.targetType))}</td>`,

        `<td style="text-align:center;">${esc(formatLocate(row))}</td>`,

        `<td style="text-align:center;">${esc(row.locateMethodLabel || row.locateMethod)}</td>`,

        `<td style="text-align:center;">${esc(formatDetectTime(row.detectStartTime))}</td>`,

        `<td style="text-align:center;">${esc(formatDetectTime(row.detectEndTime))}</td>`,

        `<td style="text-align:center;">${esc(row.detectCount ?? "—")}</td>`,

        `<td style="text-align:center;">${esc(row.role)}</td>`,

        `<td style="text-align:right;">${esc(Number.isFinite(conf) ? conf.toFixed(2) : "—")}</td>`,

        `<td style="text-align:right;">${esc(Number.isFinite(share) ? share.toFixed(1) : "—")}</td>`

      );

      return `<tr>${cells.join("")}</tr>`;

    })

    .join("");

  return `<table border="1" cellpadding="4" cellspacing="0" style="border-collapse:collapse;width:100%;font-size:10pt;">`

    + `<thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;

}



function chartBlock(dataUrl, title) {

  if (!dataUrl || !dataUrl.startsWith("data:image")) return "";

  return `

    <h3 style="color:#1e3a8a;font-size:12pt;margin-top:18px;">${esc(title)}</h3>

    <p style="text-align:center;"><img src="${dataUrl}" style="max-width:520px;height:auto;" alt="${esc(title)}" /></p>

  `;

}



function buildHtmlReport({ sceneResult, report, filteredRows, formattedRows, stats, chartImages = [] }) {

  const now = new Date().toLocaleString("zh-CN", { hour12: false });

  const summaryRows = [

    ["检测点数", sceneResult?.totalDetections],

    ["确认轨迹数", sceneResult?.confirmedTracks],

    ["优质场景数", sceneResult?.scenes?.length],

    ["报告明细行数", stats?.rowCount],

    ["涉及网络数", stats?.networkCount],

    ["聚合耗时(ms)", report?.buildTimeMs]

  ];



  const rows = formattedRows || [];

  const maxDetail = 500;

  const detailTable = buildMergedDetailTable(rows.slice(0, maxDetail));

  const extraNote =

    rows.length > maxDetail

      ? `<p style="color:#6b7280;font-size:9pt;">另有 ${rows.length - maxDetail} 行未写入，请在系统中查看完整表格。</p>`

      : "";



  return `<!DOCTYPE html>

<html xmlns:o="urn:schemas-microsoft-com:office:office"

      xmlns:w="urn:schemas-microsoft-com:office:word"

      xmlns="http://www.w3.org/TR/REC-html40">

<head>

<meta charset="utf-8"/>

<title>场景信号分析报告</title>

<!--[if gte mso 9]><xml><w:WordDocument><w:View>Print</w:View></w:WordDocument></xml><![endif]-->

<style>

  body { font-family: "Microsoft YaHei", SimSun, Arial, sans-serif; font-size: 10.5pt; color: #111; line-height: 1.5; }

  h1 { text-align: center; color: #1e3a8a; font-size: 18pt; margin-bottom: 8px; }

  h2 { color: #1e40af; font-size: 13pt; border-bottom: 1px solid #cbd5e1; padding-bottom: 4px; margin-top: 20px; }

  .meta { font-size: 9.5pt; color: #4b5563; margin-bottom: 16px; }

  th { background: #f1f5f9; font-weight: bold; }

</style>

</head>

<body>

  <h1>方位场景与信号分析综合报告</h1>

  <p class="meta">生成时间：${esc(now)} &nbsp;|&nbsp; 源数据：${esc(sceneResult?.sourceCsv)}<br/>

  输出目录：${esc(sceneResult?.outputDir)}</p>



  <h2>一、分析概要</h2>

  ${buildTable(["指标", "数值"], summaryRows)}



  <h2>二、统计图表</h2>

  ${chartBlock(chartImages[0], "目标类型分布")}

  ${chartBlock(chartImages[1], "波道分布")}

  <p style="font-size:12px;color:#555;">目标 ↔ 波道关联明细见下方报表（页面中的目标-波道表）。</p>



  <h2>三、明细数据（${filteredRows.length} 条，当前筛选）</h2>

  ${detailTable}

  ${extraNote}

</body>

</html>`;

}



function downloadBlob(blob, filename) {

  const url = URL.createObjectURL(blob);

  const a = document.createElement("a");

  a.href = url;

  a.download = filename;

  a.style.display = "none";

  document.body.appendChild(a);

  a.click();

  document.body.removeChild(a);

  URL.revokeObjectURL(url);

}



/**

 * @param {object} opts - 同 SceneResultsPanel 传入

 */

export async function exportAnalysisDocx(opts) {

  const html = buildHtmlReport(opts);

  const blob = new Blob(["\ufeff", html], {

    type: "application/msword;charset=utf-8"

  });

  const name = `场景信号分析报告_${new Date().toISOString().slice(0, 10)}.doc`;

  downloadBlob(blob, name);

}


