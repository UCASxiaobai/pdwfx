package com.pdwfx.signal.controller;

import com.pdwfx.signal.model.DirectionFindingMatchResponse;
import com.pdwfx.signal.service.DirectionFindingMatchService;
import com.scenefinder.service.DirectionFindingMatcher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 测向–定位匹配：PDW 测向 CSV + 外源雷情定位 CSV。
 */
@RestController
@RequestMapping("/api/signals/df-match")
public class DirectionFindingMatchController {

    private final DirectionFindingMatchService matchService;

    public DirectionFindingMatchController(DirectionFindingMatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DirectionFindingMatchResponse matchUpload(
            @RequestParam("bearingFile") MultipartFile bearingFile,
            @RequestParam("locateFile") MultipartFile locateFile,
            @RequestParam(required = false) Integer minFrames,
            @RequestParam(required = false) Integer maxHistoryFrames,
            @RequestParam(required = false) Double correctRate,
            @RequestParam(required = false) Double angleThresholdDeg,
            @RequestParam(required = false) Double timeThresholdSec,
            @RequestParam(required = false) Double sustainDurationSec,
            @RequestParam(required = false) Double distanceThresholdM,
            @RequestParam(required = false) Double meanSquareBeforeSec,
            @RequestParam(required = false) Double meanSquareAfterSec,
            @RequestParam(required = false) Boolean enableBearingChangeFilter,
            @RequestParam(required = false) Boolean ignoreTimeDimension
    ) throws IOException {
        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        if (minFrames != null) config.minFrames = minFrames;
        if (maxHistoryFrames != null) config.maxHistoryFrames = maxHistoryFrames;
        if (correctRate != null) config.correctRate = correctRate;
        if (angleThresholdDeg != null) config.angleThresholdDeg = angleThresholdDeg;
        if (timeThresholdSec != null) config.maxTimeDeltaMs = Math.round(timeThresholdSec * 1000);
        if (sustainDurationSec != null) config.sustainDurationMs = Math.round(sustainDurationSec * 1000);
        if (distanceThresholdM != null) config.distanceThresholdM = distanceThresholdM;
        if (meanSquareBeforeSec != null) config.meanSquareBeforeSec = meanSquareBeforeSec;
        if (meanSquareAfterSec != null) config.meanSquareAfterSec = meanSquareAfterSec;
        if (enableBearingChangeFilter != null) config.enableBearingChangeFilter = enableBearingChangeFilter;
        if (ignoreTimeDimension != null) config.ignoreTimeDimension = ignoreTimeDimension;
        return matchService.matchFiles(bearingFile, locateFile, config);
    }
}
