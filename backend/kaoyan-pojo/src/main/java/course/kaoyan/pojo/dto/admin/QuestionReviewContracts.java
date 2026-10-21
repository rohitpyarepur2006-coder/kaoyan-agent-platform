package course.kaoyan.pojo.dto.admin;

import course.kaoyan.common.enums.DomainTypes.*;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.*;
import java.util.*;

public final class QuestionReviewContracts {
    private QuestionReviewContracts() {}
    public enum Origin { self_authored, authorized_past, simulated }
    public enum Decision { approved, rejected }

    public record Input(@NotNull Subject subject, @NotNull Difficulty difficulty,
            @NotNull UUID knowledgePointId, @NotBlank @Size(max=100) String knowledgePointName,
            @NotBlank @Size(max=2000) String stem,
            @NotEmpty Map<String, @NotBlank @Size(max=1000) String> options,
            @NotBlank @Pattern(regexp="[A-D]") String correctOption,
            @NotBlank @Size(max=4000) String explanation,
            @Size(max=2000) String hintConcept, @Size(max=2000) String hintApproach,
            @NotBlank @Size(max=200) String sourceTitle, @NotBlank @Size(max=500) String sourceUrl,
            @NotBlank @Size(max=100) String sourceLocator, @NotBlank @Size(max=1000) String license,
            @NotNull @PastOrPresent LocalDate retrievedAt, @NotNull Origin questionOrigin,
            @Min(2000) @Max(2100) Integer examYear, UUID expectedVersionId) {
        @AssertTrue public boolean isOptionsValid() {
            return options != null && options.keySet().equals(Set.of("A", "B", "C", "D"));
        }
        @AssertTrue public boolean isYearValid() {
            return questionOrigin == Origin.authorized_past ? examYear != null : examYear == null;
        }
        @AssertTrue public boolean isSourceUrlValid() {
            if (sourceUrl == null || sourceUrl.contains("\\")) return false;
            try {
                URI uri = URI.create(sourceUrl);
                if (sourceUrl.startsWith("/references/"))
                    return uri.getRawAuthority() == null && !uri.isAbsolute()
                        && uri.normalize().getPath().startsWith("/references/")
                        && !uri.getPath().contains("..");
                return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
            } catch (IllegalArgumentException e) { return false; }
        }
    }
    public record ReviewInput(@NotNull UUID questionVersionId, @NotNull Decision status,
            @NotBlank @Size(max=2000) String comment) {}
    public record View(UUID questionId, UUID questionVersionId, String status, Input content,
            String createdBy, Instant createdAt, String reviewedBy, Instant reviewedAt, String reviewComment) {}
    public record Page(List<View> items, int page, int totalPages, long totalItems) {}
    public record EventView(UUID id, UUID questionVersionId, String reviewerId, String status,
            String comment, Instant createdAt) {}
}
