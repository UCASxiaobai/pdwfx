package com.scenefinder.model;

/**
 * 优质场景类型。
 * <ul>
 *   <li>{@link #TRACK_CONTINUOUS} — 多条连续变化的方位轨迹并存（原 bearing-scene-finder 逻辑）</li>
 *   <li>{@link #MULTI_DEVICE_POLLING} — 多设备在同一时刻（轮询周期内）从不同方位同时发信，空域混叠但可通过周期分辨</li>
 * </ul>
 */
public enum SceneType {
    TRACK_CONTINUOUS,
    MULTI_DEVICE_POLLING
}
