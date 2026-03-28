package com.example.report;

import com.example.model.AnalysisReport;
import com.example.render.ConsoleReportRenderer;
import com.example.render.HtmlReportRenderer;
import com.example.render.JsonReportRenderer;
import com.example.render.MarkdownReportRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves and reads report bundles on disk for later CLI access.
 */
public class ReportBundleService {

    private final Path baseDirectory;
    private final ConsoleReportRenderer consoleRenderer;
    private final JsonReportRenderer jsonRenderer;
    private final MarkdownReportRenderer markdownRenderer;
    private final HtmlReportRenderer htmlRenderer;

    public ReportBundleService(Path baseDirectory) {
        this(
            baseDirectory,
            new ConsoleReportRenderer(),
            new JsonReportRenderer(),
            new MarkdownReportRenderer(),
            new HtmlReportRenderer()
        );
    }

    public ReportBundleService(
        Path baseDirectory,
        ConsoleReportRenderer consoleRenderer,
        JsonReportRenderer jsonRenderer,
        MarkdownReportRenderer markdownRenderer,
        HtmlReportRenderer htmlRenderer
    ) {
        this.baseDirectory = baseDirectory;
        this.consoleRenderer = consoleRenderer;
        this.jsonRenderer = jsonRenderer;
        this.markdownRenderer = markdownRenderer;
        this.htmlRenderer = htmlRenderer;
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

    public Path bundlePath(String analysisId) {
        return baseDirectory.resolve(analysisId);
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
}
