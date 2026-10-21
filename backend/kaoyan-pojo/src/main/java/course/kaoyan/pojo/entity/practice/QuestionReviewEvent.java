package course.kaoyan.pojo.entity.practice;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="question_review_events", uniqueConstraints=@UniqueConstraint(name="review_event_version", columnNames="versionId"),
    indexes=@Index(name="review_event_question", columnList="questionId,createdAt"))
public class QuestionReviewEvent {
    @Id public String id = UUID.randomUUID().toString();
    @Column(nullable=false, updatable=false) public String questionId;
    @Column(nullable=false, updatable=false) public String versionId;
    @Column(nullable=false, updatable=false) public String reviewerId;
    @Column(nullable=false, updatable=false, length=16) public String status;
    @Column(nullable=false, updatable=false, length=2000) public String comment;
    @Column(nullable=false, updatable=false) public Instant createdAt;
    protected QuestionReviewEvent() {}
    public QuestionReviewEvent(QuestionReviewState state) {
        questionId = state.questionId; versionId = state.versionId; reviewerId = state.reviewedBy;
        status = state.status; comment = state.reviewComment; createdAt = state.reviewedAt;
    }
    @PreUpdate @PreRemove private void rejectMutation() { throw new IllegalStateException("审核日志只能追加"); }
}
