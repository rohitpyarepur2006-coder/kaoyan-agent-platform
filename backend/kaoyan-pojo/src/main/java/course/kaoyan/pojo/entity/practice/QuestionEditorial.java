package course.kaoyan.pojo.entity.practice;

import jakarta.persistence.*;

/** Editing pointer is separate from the currently playable Question.versionId. */
@Entity
@Table(name="question_editorials")
public class QuestionEditorial {
    @Id public String questionId;
    @Column(nullable=false) public String latestVersionId;
    protected QuestionEditorial() {}
    public QuestionEditorial(String questionId, String versionId) {
        this.questionId = questionId; this.latestVersionId = versionId;
    }
}
