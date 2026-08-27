package com.zhitu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class Requests {
    private Requests() {
    }

    public record MatchRequest(@NotNull Long resumeId, @NotNull Long roleId) {
    }

    public record LearningRequest(
            @NotNull Long matchId,
            @Min(1) @Max(52) Integer weeks,
            @Min(1) @Max(40) Integer hoursPerWeek,
            String planMode,
            List<String> planModes
    ) {
    }

    public record AuditDecision(
            @NotBlank String action,
            String reviewer,
            String comment,
            Map<String, Object> patch
    ) {
    }

    public record ChatRequest(@NotBlank String message, String sessionId) {
    }

    public record TextImport(
            @NotBlank String sourceName,
            String sourceType,
            String sourceUrl,
            @NotBlank String content
    ) {
    }

    public record UrlImport(@NotBlank String url, String sourceType) {
    }

    public record GraphFilter(String techStack, String level, Integer limit) {
    }

    public record HoldoutRequest(
            @Min(2000) @Max(2100) Integer year,
            @Min(100) @Max(100000) Integer size,
            String seed,
            Boolean reset
    ) {
    }

    public record TemporalBacktestRequest(
            @NotNull @Min(2000) @Max(2099) Integer startYear,
            @NotNull @Min(2001) @Max(2100) Integer endYear,
            @Min(5) @Max(200) Integer topK,
            @Min(2) @Max(100) Integer minSupport
    ) {
    }

    public record GovernedJobUpdate(
            String titleStandard,
            String company,
            String city,
            Integer publishedYear,
            String techStack,
            String levelName,
            String descriptionClean,
            Boolean validForAnalysis
    ) {
    }

    public record GovernedSkillCreate(
            @NotBlank String skillName,
            String techStack,
            String category,
            String requirementType,
            Double confidence
    ) {
    }
}
