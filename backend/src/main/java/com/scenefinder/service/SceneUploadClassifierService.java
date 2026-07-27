package com.scenefinder.service;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.imports.ImportFormatException;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.pdwfx.signal.service.ExternalTargetLocateImportService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 上传批次中区分 PDW 侦获 CSV 与外源目标定位 CSV。
 */
@Service
public class SceneUploadClassifierService {

    private final ExternalTargetLocateImportService externalImportService;

    public SceneUploadClassifierService(ExternalTargetLocateImportService externalImportService) {
        this.externalImportService = externalImportService;
    }

    public UploadClassification classify(Path inputPath) throws IOException {
        List<Path> csvFiles = listCsvFiles(inputPath);
        if (csvFiles.isEmpty()) {
            throw new IllegalArgumentException("未找到 CSV 文件: " + inputPath);
        }

        List<Path> pdwFiles = new ArrayList<>();
        List<Path> externalFiles = new ArrayList<>();
        List<ExternalTargetFix> externalFixes = new ArrayList<>();

        for (Path csv : csvFiles) {
            ImportFormat format = ImportFormatDetector.detectFromPath(csv);
            String name = csv.getFileName().toString();
            switch (format) {
                case PDW_TABLE:
                    pdwFiles.add(csv);
                    break;
                case EXTERNAL_TARGET_LOCATE:
                    externalFiles.add(csv);
                    externalFixes.addAll(externalImportService.parse(csv));
                    break;
                case STANDARD:
                    throw new ImportFormatException(
                            "文件「" + name + "」为标准英文字段格式，场景筛选需 PDW 表（pl/xhfw/zcsj）。"
                                    + " 请转换为 PrcFf 格式或与 PDW 文件一并上传。",
                            format,
                            name
                    );
                default:
                    throw new ImportFormatException(
                            "文件「" + name + "」格式无法识别。"
                                    + " 支持：PDW 侦获表（pl/xhfw/zcsj）、外源定位表（detectTime/longitude/latitude）。",
                            ImportFormat.UNKNOWN,
                            name
                    );
            }
        }

        if (pdwFiles.isEmpty()) {
            throw new ImportFormatException(
                    "未找到 PDW 侦获 CSV。外源定位文件（如雷情导入）不能单独用于场景筛选，请与 PDW 数据一并上传。",
                    ImportFormat.EXTERNAL_TARGET_LOCATE,
                    externalFiles.isEmpty() ? null : externalFiles.get(0).getFileName().toString()
            );
        }

        Path pdwInput = pdwFiles.size() == 1 && csvFiles.size() == 1
                ? pdwFiles.get(0)
                : materializePdwOnlyDir(pdwFiles);

        return new UploadClassification(pdwInput, externalFiles, externalFixes);
    }

    private static Path materializePdwOnlyDir(List<Path> pdwFiles) throws IOException {
        Path dir = Files.createTempDirectory("scene-pdw-only-");
        for (Path src : pdwFiles) {
            String name = src.getFileName().toString();
            Path target = dir.resolve(name);
            int suffix = 1;
            while (Files.exists(target)) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : ".csv";
                target = dir.resolve(base + "_" + suffix++ + ext);
            }
            Files.copy(src, target);
        }
        return dir;
    }

    private static List<Path> listCsvFiles(Path inputPath) throws IOException {
        Path normalized = inputPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            return Collections.singletonList(normalized);
        }
        try (Stream<Path> stream = Files.list(normalized)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    public static final class UploadClassification {
        private final Path pdwInputPath;
        private final List<Path> externalFiles;
        private final List<ExternalTargetFix> externalFixes;

        public UploadClassification(
                Path pdwInputPath,
                List<Path> externalFiles,
                List<ExternalTargetFix> externalFixes
        ) {
            this.pdwInputPath = pdwInputPath;
            this.externalFiles = externalFiles;
            this.externalFixes = externalFixes;
        }

        public Path getPdwInputPath() {
            return pdwInputPath;
        }

        public List<Path> getExternalFiles() {
            return externalFiles;
        }

        public List<ExternalTargetFix> getExternalFixes() {
            return externalFixes;
        }
    }
}
