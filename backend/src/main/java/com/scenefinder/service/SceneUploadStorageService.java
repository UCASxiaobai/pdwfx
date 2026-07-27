package com.scenefinder.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 将上传的一个或多个 CSV 保存为临时文件或临时目录，供场景分析读取。
 */
@Service
public class SceneUploadStorageService {

    public Path storeUploads(MultipartFile singleFile, List<MultipartFile> multipleFiles) throws IOException {
        List<MultipartFile> all = new ArrayList<>();
        if (multipleFiles != null) {
            for (MultipartFile f : multipleFiles) {
                if (f != null && !f.isEmpty()) {
                    all.add(f);
                }
            }
        }
        if (singleFile != null && !singleFile.isEmpty()) {
            all.add(singleFile);
        }
        if (all.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一个 CSV 文件");
        }
        if (all.size() == 1) {
            return storeSingleCsv(all.get(0));
        }
        Path dir = Files.createTempDirectory("scene-upload-batch-");
        for (MultipartFile file : all) {
            String original = file.getOriginalFilename();
            String name = original != null && !original.trim().isEmpty()
                    ? Paths.get(original).getFileName().toString()
                    : "upload.csv";
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                name = name + ".csv";
            }
            Path target = dir.resolve(sanitizeFileName(name));
            int suffix = 1;
            while (Files.exists(target)) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : ".csv";
                target = dir.resolve(sanitizeFileName(base + "_" + suffix++ + ext));
            }
            file.transferTo(target);
        }
        return dir;
    }

    private static Path storeSingleCsv(MultipartFile file) throws IOException {
        Path temp = Files.createTempFile("scene-upload-", ".csv");
        file.transferTo(temp);
        return temp;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static List<Path> listCsvFilesSorted(Path inputPath) throws IOException {
        if (Files.isRegularFile(inputPath)) {
            List<Path> one = new ArrayList<>();
            one.add(inputPath);
            return one;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(inputPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
        }
    }
}
