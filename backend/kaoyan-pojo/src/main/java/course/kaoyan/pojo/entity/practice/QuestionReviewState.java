package course.kaoyan.pojo.entity.practice;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name="question_review_states", indexes=@Index(name="review_state_question", columnList="questionId"))
public class QuestionReviewState {
    @Id public String versionId;
    @Column(nullable=false, updatable=false) public String questionId;
    @Column(nullable=false, length=16) public String status = "draft";
    @Column(nullable=false, updatable=false) public String createdBy;
    @Column(nullable=false, updatable=false) public Instant createdAt = Instant.now();
    @Column(nullable=false, updatable=false, length=1000) public String license;
    @Column(nullable=false, updatable=false) public LocalDate retrievedAt;
    @Column(nullable=false, updatable=false, length=32) public String questionOrigin;
    @Column(updatable=false) public Integer examYear;
    public String reviewedBy;
    public Instant reviewedAt;
    @Column(length=2000) public String reviewComment;
    protected QuestionReviewState() {}
    public QuestionReviewState(String versionId, String questionId, String createdBy, String license,
            LocalDate retrievedAt, String origin, Integer examYear) {
        this.versionId = versionId; this.questionId = questionId; this.createdBy = createdBy;
        this.license = license; this.retrievedAt = retrievedAt; this.questionOrigin = origin; this.examYear = examYear;
    }
}
