package com.refacto.migration.transformation;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.UnifiedDiffUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Service de génération de diffs unifiés (Sections 39 et 40).
 */
public class DiffGeneratorService {

    public String generateUnifiedDiff(String originalFilePath, String originalContent, String revisedContent) {
        if (originalContent == null) originalContent = "";
        if (revisedContent == null) revisedContent = "";

        List<String> originalLines = Arrays.asList(originalContent.split("\\r?\\n"));
        List<String> revisedLines = Arrays.asList(revisedContent.split("\\r?\\n"));

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + originalFilePath,
                "b/" + originalFilePath,
                originalLines,
                patch,
                3
        );

        return String.join("\n", unifiedDiff);
    }

    /**
     * Vérifie si la modification contient de réels changements de code
     * et non de simples variations d'espacement, d'indentation ou de saut de ligne.
     */
    public boolean hasMeaningfulChanges(String originalContent, String revisedContent) {
        if (originalContent == null && revisedContent == null) return false;
        if (originalContent == null || revisedContent == null) return true;

        String normOriginal = originalContent.replaceAll("\\s+", "");
        String normRevised = revisedContent.replaceAll("\\s+", "");

        return !normOriginal.equals(normRevised);
    }
}
