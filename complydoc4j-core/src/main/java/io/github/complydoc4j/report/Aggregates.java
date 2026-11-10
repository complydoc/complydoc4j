package io.github.complydoc4j.report;

import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The report's {@code aggregate}: what a decision maker reads first. The security figures
 * are counted as Python counts them; the cost and readiness figures are null, as those are
 * not produced by the Java version.
 */
public final class Aggregates {

    private Aggregates() {}

    public static Map<String, Object> of(List<Report.Document> documents) {
        Map<String, Integer> formats = new TreeMap<>();
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, Integer> bySeverity = new TreeMap<>();
        Map<String, String> notScanned = new TreeMap<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        int pages = 0;
        int unreadable = 0;
        int sensitiveTotal = 0;
        int withSensitive = 0;
        int contentTotal = 0;
        int contentHigh = 0;
        int withContent = 0;
        int unchecked = 0;
        double read = 0;
        double analyse = 0;
        double scan = 0;
        double total = 0;
        for (Report.Document document : documents) {
            formats.merge(document.format(), 1, Integer::sum);
            pages += document.pageCount();
            Report.Sensitive sensitive = document.sensitive();
            if (sensitive != null) {
                unreadable += sensitive.unreadablePages().size();
                for (SensitiveMatch match : sensitive.matches()) {
                    byCategory.merge(match.category(), 1, Integer::sum);
                    bySeverity.merge(match.severity(), 1, Integer::sum);
                }
                sensitiveTotal += sensitive.matches().size();
                withSensitive += sensitive.matches().isEmpty() ? 0 : 1;
                for (Scanner.UnscannedCategory category : sensitive.unscannedCategories()) {
                    notScanned.putIfAbsent(category.category(), category.reason());
                }
            }
            for (ContentFinding finding : document.contentFindings()) {
                matrix.computeIfAbsent(finding.instruction(), k -> new TreeMap<>())
                        .merge(finding.visibility(), 1, Integer::sum);
                contentHigh += finding.severity().equals("high") ? 1 : 0;
            }
            contentTotal += document.contentFindings().size();
            withContent += document.contentFindings().isEmpty() ? 0 : 1;
            unchecked += document.visibilityChecked() ? 0 : 1;
            Report.Timing timing = document.timing();
            read += timing.readSeconds();
            analyse += timing.analyseSeconds();
            scan += timing.scanSeconds();
            total += timing.totalSeconds();
        }
        int count = documents.size();
        double perDocument = count == 0 ? 0.0 : DocumentAudit.round(total / count, 3);
        Map<String, Object> projected = new LinkedHashMap<>();
        for (int n : new int[] {100, 1000, 10000, 100000}) {
            projected.put(String.valueOf(n), DocumentAudit.round(perDocument * n, 1));
        }

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("documents_audited", count);
        aggregate.put("documents_skipped", 0);
        aggregate.put("pages_total", pages);
        aggregate.put("pages_unreadable", unreadable);
        aggregate.put("formats", formats);
        aggregate.put("total_text_path_usd", null);
        aggregate.put("total_vision_path_usd", null);
        aggregate.put("monthly_text_usd", null);
        aggregate.put("monthly_vision_usd", null);
        aggregate.put("annual_text_usd", null);
        aggregate.put("annual_vision_usd", null);
        aggregate.put("currency", "USD");
        aggregate.put("signal_distribution", Map.of());
        aggregate.put("readiness_bands", Map.of());
        aggregate.put("mean_readiness_score", null);
        aggregate.put("sensitive_by_category", byCategory);
        aggregate.put("sensitive_by_severity", bySeverity);
        aggregate.put("sensitive_total", sensitiveTotal);
        aggregate.put("documents_with_sensitive_data", withSensitive);
        aggregate.put("categories_not_scanned", notScanned);
        aggregate.put("content_matrix", matrix);
        aggregate.put("content_findings_total", contentTotal);
        aggregate.put("content_findings_high", contentHigh);
        aggregate.put("documents_with_content_findings", withContent);
        aggregate.put("documents_visibility_unchecked", unchecked);
        aggregate.put("ignored_total", 0);
        aggregate.put("total_seconds", DocumentAudit.round(total, 3));
        aggregate.put("seconds_per_document", perDocument);
        aggregate.put("seconds_per_page", pages == 0 ? 0.0 : DocumentAudit.round(total / pages, 3));
        aggregate.put("ocr_pages", 0);
        aggregate.put("ocr_seconds", 0.0);
        aggregate.put("ocr_pages_per_second", null);
        aggregate.put("hours_per_1000_documents", DocumentAudit.round(perDocument * 1000 / 3600, 2));
        aggregate.put("read_seconds", DocumentAudit.round(read, 3));
        aggregate.put("analyse_seconds", DocumentAudit.round(analyse, 3));
        aggregate.put("scan_seconds", DocumentAudit.round(scan, 3));
        aggregate.put("projected_seconds", projected);
        return aggregate;
    }
}
