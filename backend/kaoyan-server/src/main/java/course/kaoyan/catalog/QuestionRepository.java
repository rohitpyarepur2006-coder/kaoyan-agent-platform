package course.kaoyan.catalog;

import course.kaoyan.pojo.entity.practice.Question;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface QuestionRepository extends JpaRepository<Question, String> {
    List<Question> findByActiveTrueOrderByStableOrderAsc();
    Optional<Question> findByIdAndActiveTrue(String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id")
    Optional<Question> findForEditing(@Param("id") String id);
}
