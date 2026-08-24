/** 精细匹配默认：短时差 + 连续持续窗 */
export const FINE_DF_MATCH_DEFAULTS = {
  mode: "fine",
  /** 测向与定位允许的最大时差（秒） */
  timeThresholdSec: 2,
  /** 同一测向批持续指向同一目标的最短时间（秒） */
  sustainDurationSec: 10,
  /** 粗匹配锁定所需指向次数；精细模式不使用 */
  minHits: 1,
  /** 方位差门限（度） */
  angleThresholdDeg: 0.5,
  /** 距离门限（千米） */
  distanceThresholdKm: 400,
  /** 忽略测向–定位时差，仅按方位/距离门限单帧关联 */
  ignoreTimeDimension: false
};

/** 粗匹配默认：宽时差 + 整批计票，minHits=1 表示指向即匹配 */
export const COARSE_DF_MATCH_DEFAULTS = {
  mode: "coarse",
  timeThresholdSec: 600,
  sustainDurationSec: 10,
  minHits: 1,
  angleThresholdDeg: 0.5,
  distanceThresholdKm: 400,
  ignoreTimeDimension: false
};

/** 测向–定位匹配默认超参数（与后端 DirectionFindingMatcher.MatchConfig 一致） */
export const DEFAULT_DF_MATCH_PARAMS = { ...FINE_DF_MATCH_DEFAULTS };

export function paramsForDfMatchMode(mode) {
  return mode === "coarse" ? { ...COARSE_DF_MATCH_DEFAULTS } : { ...FINE_DF_MATCH_DEFAULTS };
}

export function appendDfMatchParams(fd, params) {
  if (!fd || !params) return;
  appendNum(fd, "timeThresholdSec", params.timeThresholdSec);
  appendNum(fd, "sustainDurationSec", params.sustainDurationSec);
  appendNum(fd, "angleThresholdDeg", params.angleThresholdDeg);
  if (params.distanceThresholdKm != null && params.distanceThresholdKm !== "") {
    fd.append("distanceThresholdM", String(Number(params.distanceThresholdKm) * 1000));
  }
  if (params.ignoreTimeDimension === true) {
    fd.append("ignoreTimeDimension", "true");
  }
  if (params.mode === "coarse" || params.coarseMatch === true) {
    fd.append("coarseMatch", "true");
    appendNum(fd, "minHits", params.minHits);
  }
  if (params.exclusiveAssign === false) {
    fd.append("exclusiveAssign", "false");
  }
  if (params.outputDir) {
    fd.append("outputDir", String(params.outputDir));
  }
}

function appendNum(fd, key, value) {
  if (value !== null && value !== undefined && value !== "") {
    fd.append(key, String(value));
  }
}
