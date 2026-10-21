package course.kaoyan.admin;
import course.kaoyan.pojo.entity.practice.QuestionReviewEvent;
import java.util.List;
import org.springframework.data.repository.Repository;
public interface QuestionReviewEventRepository extends Repository<QuestionReviewEvent, String> {
    List<QuestionReviewEvent> findByQuestionIdOrderByCreatedAtAscIdAsc(String questionId);
}
