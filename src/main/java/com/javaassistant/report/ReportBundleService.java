package com.javaassistant.report;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.SeverityLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saves and reads report bundles on disk for later CLI access.
 */
public class ReportBundleService {

    private final Path baseDirectory;
    private final ConsoleReportRenderer consoleRenderer;
    private final JsonReportRenderer jsonRenderer;
    private final MarkdownReportRenderer markdownRenderer;
    private final HtmlReportRenderer htmlRenderer;
    private final AnalysisReportJsonCodec jsonCodec;

    public ReportBundleService(Path baseDirectory) {
        this(
            baseDirectory,
            new ConsoleReportRenderer(),
            new JsonReportRenderer(),
            new MarkdownReportRenderer(),
            new HtmlReportRenderer(),
            new AnalysisReportJsonCodec()
        );
    }

    public ReportBundleService(
        Path baseDirectory,
        ConsoleReportRenderer consoleRenderer,
        JsonReportRenderer jsonRenderer,
        MarkdownReportRenderer markdownRenderer,
        HtmlReportRenderer htmlRenderer,
        AnalysisReportJsonCodec jsonCodec
    ) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.consoleRenderer = consoleRenderer;
        this.jsonRenderer = jsonRenderer;
        this.markdownRenderer = markdownRenderer;
        this.htmlRenderer = htmlRenderer;
        this.jsonCodec = jsonCodec;
    }

    public Path save(AnalysisReport report) throws IOException {
        Path bundleDirectory = baseDirectory.resolve(report.analysisId());
        Files.createDirectories(bundleDirectory);
        Files.writeString(bundleDirectory.resolve("report.txt"), consoleRenderer.render(report));
        Files.writeString(bundleDirectory.resolve("report.json"), jsonRenderer.render(report));
        Files.writeString(bundleDirectory.resolve("report.md"), markdownRenderer.render(report));
        Files.writeString(bundleDirectory.resolve("report.html"), htmlRenderer.render(report));
        return bundleDirectory;
    }

    public String readReport(String analysisId, String format) throws IOException {
        String normalizedFormat = normalizeFormat(format);
        Path reportPath = baseDirectory.resolve(analysisId).resolve(fileNameForFormat(normalizedFormat));
        if (!Files.exists(reportPath)) {
            throw new IOException("Saved report not found: " + reportPath);
        }
        return Files.readString(reportPath);
    }

    public boolean exists(String analysisId) {
        return Files.exists(baseDirectory.resolve(analysisId));
    }

    public AnalysisReport load(String analysisId) throws IOException {
        Path reportPath = baseDirectory.resolve(analysisId).resolve("report.json");
        if (!Files.exists(reportPath)) {
            throw new IOException("Saved report not found: " + reportPath);
        }
        return jsonCodec.fromJson(Files.readString(reportPath));
    }

    public Path bundlePath(String analysisId) {
        return baseDirectory.resolve(analysisId);
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public ReportCatalogResult listCatalogEntries() throws IOException {
        return listCatalogEntries(null, null);
    }

    public ReportCatalogResult listCatalogEntries(SeverityLevel severityFilter, ArtifactType artifactTypeFilter) throws IOException {
        if (!Files.exists(baseDirectory)) {
            return new ReportCatalogResult(List.of(), List.of());
        }
        if (!Files.isDirectory(baseDirectory)) {
            throw new IOException("Report bundle directory is not a directory: " + baseDirectory);
        }

        List<ReportCatalogEntry> entries = new ArrayList<>();
        List<String> skippedBundles = new ArrayList<>();

        try (Stream<Path> bundleStream = Files.list(baseDirectory)) {
            for (Path bundleDirectory : bundleStream.filter(Files::isDirectory).toList()) {
                try {
                    AnalysisReport report = loadBundleDirectory(bundleDirectory);
                    ReportCatalogEntry entry = toCatalogEntry(report, bundleDirectory);
                    if (entry.matches(severityFilter, artifactTypeFilter)) {
                        entries.add(entry);
                    }
                } catch (Exception exception) {
                    skippedBundles.add(bundleDirectory.getFileName() + ": " + exception.getMessage());
                }
            }
        }

        entries.sort(
            Comparator.comparing(ReportCatalogEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ReportCatalogEntry::analysisId, Comparator.nullsLast(Comparator.reverseOrder()))
        );
        skippedBundles.sort(String::compareTo);

        return new ReportCatalogResult(entries, skippedBundles);
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "text";
        }
        return switch (format.toLowerCase()) {
            case "text", "txt" -> "text";
            case "json" -> "json";
            case "markdown", "md" -> "markdown";
            case "html" -> "html";
            default -> throw new IllegalArgumentException("Unsupported report format: " + format);
        };
    }

    private String fileNameForFormat(String format) {
        return switch (format) {
            case "text" -> "report.txt";
            case "json" -> "report.json";
            case "markdown" -> "report.md";
            case "html" -> "report.html";
            default -> throw new IllegalArgumentException("Unsupported report format: " + format);
        };
    }

    private AnalysisReport loadBundleDirectory(Path bundleDirectory) throws IOException {
        Path reportPath = bundleDirectory.resolve("report.json");
        if (!Files.exists(reportPath)) {
            throw new IOException("missing report.json");
        }
        return jsonCodec.fromJson(Files.readString(reportPath));
    }

    @SuppressWarnings("unchecked")
    private ReportCatalogEntry toCatalogEntry(AnalysisReport report, Path bundleDirectory) {
        Map<String, Object> summary = report.catalogSummary();
        List<ArtifactType> artifactTypes = new ArrayList<>();
        Object artifactTypeValue = summary.get("artifactTypes");
        if (artifactTypeValue instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    artifactTypes.add(ArtifactType.valueOf(String.valueOf(item)));
                }
            }
        }

        return new ReportCatalogEntry(
            stringValue(summary.get("analysisId")),
            dateTimeValue(summary.get("createdAt")),
            enumValue(SeverityLevel.class, stringValue(summary.get("overallSeverity"))),
            enumValue(ConfidenceLevel.class, stringValue(summary.get("confidence"))),
            artifactTypes,
            stringValue(summary.get("redactionProfile")),
            intValue(summary.get("inputArtifactCount")),
            booleanValue(summary.get("hasCorrelationResult")),
            bundleDirectory
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDateTime dateTimeValue(Object value) {
        return value == null ? null : LocalDateTime.parse(String.valueOf(value));
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumClass, String value) {
        return value == null ? null : Enum.valueOf(enumClass, value);
    }
}
