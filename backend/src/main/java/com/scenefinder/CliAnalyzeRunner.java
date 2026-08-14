package com.scenefinder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenefinder.model.SceneFinderResult;
import com.scenefinder.service.SceneFinderService;
import com.scenefinder.service.AnalyzeOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 命令行分析入口：传入 {@code --csv=路径} 时执行一次分析并退出（不长期占用 Web 端口）。
 * <p>
 * 示例：
 * <pre>
 *   java -jar bearing-scene-finder.jar --csv=data.csv --topKTrackScenes=15 --topKPollingScenes=10
 * </pre>
 * 参数名与 {@link com.scenefinder.config.SceneFinderProperties} / {@link AnalyzeOptions} 字段对应（驼峰）。
 * 未传的参数使用 application.yml 默认值。
 * </p>
 */
@Component
public class CliAnalyzeRunner implements ApplicationRunner {

    private final SceneFinderService sceneFinderService;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext applicationContext;

    public CliAnalyzeRunner(
            SceneFinderService sceneFinderService,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext applicationContext
    ) {
        this.sceneFinderService = sceneFinderService;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("csv")) {
            return;
        }

        String csv = args.getOptionValues("csv").get(0);
        Path csvPath = Paths.get(csv);
        if (!Files.exists(csvPath)) {
            throw new IllegalArgumentException("CSV path not found: " + csvPath);
        }

        AnalyzeOptions options = new AnalyzeOptions(
                parseDouble(args, "frameSeconds"),
                parseDouble(args, "bearingClusterGapDeg"),
                parseDouble(args, "associationGateDeg"),
                parseInt(args, "maxMissedFrames"),
                parseDouble(args, "minTrackSeconds"),
                parseInt(args, "minTrackPoints"),
                parseDouble(args, "windowSeconds"),
                parseDouble(args, "windowStepSeconds"),
                parseInt(args, "minTracksInScene"),
                parseInt(args, "topKScenes"),
                parseInt(args, "topKTrackScenes"),
                parseInt(args, "topKPollingScenes"),
                parseDouble(args, "minSeparationDeg"),
                parseDouble(args, "freqMin"),
                parseDouble(args, "freqMax"),
                parseDouble(args, "freqTolerance"),
                parseDouble(args, "freqClusterGapMhz"),
                parseDouble(args, "sceneFreqBandGapMhz"),
                parseDouble(args, "mergeMaxGapSeconds"),
                args.containsOption("outputDir") ? args.getOptionValues("outputDir").get(0) : null,
                null,
                parseBoolean(args, "fullSpanWindow")
        );

        SceneFinderResult result = sceneFinderService.analyze(csvPath, options);
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        result.getExportedFiles().stream()
                .filter(path -> path.endsWith("visualization.html"))
                .findFirst()
                .ifPresent(path -> System.out.println("可视化报告: file:///" + Paths.get(path).toAbsolutePath().toString().replace('\\', '/')));
        System.exit(SpringApplication.exit(applicationContext, () -> 0));
    }

    private Double parseDouble(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        return Double.parseDouble(args.getOptionValues(name).get(0));
    }

    private Integer parseInt(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        return Integer.parseInt(args.getOptionValues(name).get(0));
    }

    private Boolean parseBoolean(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        return Boolean.parseBoolean(args.getOptionValues(name).get(0));
    }
}
