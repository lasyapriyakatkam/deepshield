package com.deepshield.backend.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Represents one row in the analysis breakdown table.
 * Example: "CNN Face Classification" — status: "FAKE" — score: 0.94
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisDetail {

    /** Name of the check (e.g., "CNN Face Classification") */
    private String checkName;

    /** Status tag (e.g., "FAKE", "WARN", "PASS") */
    private String status;

    /** Numeric score from 0.0 to 1.0 */
    private Double score;

    /** Brief description of what was found */
    private String description;
}