/** 测向–定位匹配默认超参数（与后端 DirectionFindingMatcher.MatchConfig 一致） */
export const DEFAULT_DF_MATCH_PARAMS = {
  /** 测向与定位允许的最大时差（秒） */
  timeThresholdSec: 2,
  /** 同一测向批持续指向同一目标的最短时间（秒） */
  sustainDurationSec: 10,
  /** 方位差门限（度） */
  angleThresholdDeg: 0.5,
  /** 距离门限（千米） */
  distanceThresholdKm: 400,
  /** 忽略测向–定位时差，仅按方位/距离门限单帧关联 */
  ignoreTimeDimension: false
};

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
}

function appendNum(fd, key, value) {
  if (value !== null && value !== undefined && value !== "") {
    fd.append(key, String(value));
  }
}
