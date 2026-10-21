package course.kaoyan.admin;

import course.kaoyan.auth.AccountRepository;
import course.kaoyan.catalog.*;
import course.kaoyan.common.enums.DomainTypes.*;
import course.kaoyan.common.exception.ApiException;
import course.kaoyan.pojo.dto.admin.QuestionReviewContracts.*;
import course.kaoyan.pojo.entity.practice.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class QuestionReviewService {
    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final QuestionVersionStore store;
    private final QuestionEditorialRepository editorials;
    private final QuestionReviewStateRepository states;
    private final QuestionReviewEventRepository events;
    private final AccountRepository accounts;
    private final EntityManager entityManager;

    public QuestionReviewService(QuestionRepository questions, QuestionVersionRepository versions,
            QuestionVersionStore store, QuestionEditorialRepository editorials, QuestionReviewStateRepository states,
            QuestionReviewEventRepository events, AccountRepository accounts, EntityManager entityManager) {
        this.questions=questions; this.versions=versions; this.store=store; this.editorials=editorials;
        this.states=states; this.events=events; this.accounts=accounts; this.entityManager=entityManager;
    }

    public Page list(String actor, int page) {
        admin(actor);
        var result = questions.findAll(PageRequest.of(page, 20, Sort.by("stableOrder", "id")));
        return new Page(result.stream().map(q -> view(q.id, latest(q))).toList(),
            page, result.getTotalPages(), result.getTotalElements());
    }
    public View detail(String actor, UUID id) {
        admin(actor); Question q = require(id.toString()); return view(q.id, latest(q));
    }
    public List<View> versions(String actor, UUID id) {
        admin(actor); require(id.toString());
        return versions.findByQuestionId(id.toString()).stream().map(v -> view(id.toString(), v.getId()))
            .sorted(Comparator.comparing((View v) -> v.createdAt() == null ? Instant.EPOCH : v.createdAt())
                .thenComparing(v -> v.questionVersionId().toString())).toList();
    }
    public List<EventView> history(String actor, UUID id) {
        admin(actor); require(id.toString());
        return events.findByQuestionIdOrderByCreatedAtAscIdAsc(id.toString()).stream()
            .map(e -> new EventView(UUID.fromString(e.id), UUID.fromString(e.versionId), e.reviewerId,
                e.status, e.comment, e.createdAt)).toList();
    }
    @Transactional public View create(String actor, Input input) {
        admin(actor);
        if (input.expectedVersionId() != null) throw invalid("创建新题不接受旧版本编号");
        Question q = content(input, UUID.randomUUID().toString());
        q.active = false;
        questions.saveAndFlush(q);
        saveDraft(actor, q, input);
        return view(q.id, q.versionId);
    }
    @Transactional public View revise(String actor, UUID id, Input input) {
        admin(actor);
        Question current = editing(id.toString());
        if (input.expectedVersionId() == null) throw invalid("修订必须提供expectedVersionId");
        if (!latest(current).equals(input.expectedVersionId().toString()))
            throw new ApiException(409, "STALE_QUESTION_VERSION", "已有更新的编辑版本，请重新加载后修订");
        Question draft = content(input, current.id);
        // Do not save this detached draft over the student's current Question projection.
        saveDraft(actor, draft, input);
        return view(draft.id, draft.versionId);
    }
    @Transactional public View review(String actor, UUID id, ReviewInput input) {
        admin(actor); Question current = editing(id.toString());
        String versionId = input.questionVersionId().toString();
        if (!latest(current).equals(versionId))
            throw new ApiException(409, "STALE_QUESTION_VERSION", "只能审核该题最新编辑版本");
        QuestionReviewState state = states.findById(versionId)
            .filter(s -> s.questionId.equals(current.id))
            .orElseThrow(() -> new ApiException(409, "QUESTION_NOT_DRAFT", "请先保存完整来源与权限信息，创建待审核版本"));
        if (!state.status.equals("draft"))
            throw new ApiException(409, "REVIEW_ALREADY_DECIDED", "该版本已有审核结果，请修订为新版本后再次审核");
        state.status = input.status().name(); state.reviewedBy = actor;
        state.reviewedAt = Instant.now(); state.reviewComment = input.comment().trim();
        states.saveAndFlush(state);
        entityManager.persist(new QuestionReviewEvent(state)); entityManager.flush();
        return view(current.id, versionId);
    }
    private void saveDraft(String actor, Question draft, Input input) {
        store.record(draft);
        states.saveAndFlush(new QuestionReviewState(draft.versionId, draft.id, actor, input.license().trim(),
            input.retrievedAt(), input.questionOrigin().name(), input.examYear()));
        editorials.saveAndFlush(new QuestionEditorial(draft.id, draft.versionId));
    }
    private Question content(Input i, String questionId) {
        return new Question(questionId, UUID.randomUUID().toString(), i.subject().name(), i.difficulty().name(),
            i.knowledgePointId().toString(), i.knowledgePointName().trim(), i.stem(), i.options().get("A"),
            i.options().get("B"), i.options().get("C"), i.options().get("D"), i.correctOption(), i.explanation(),
            i.hintConcept(), i.hintApproach(), i.sourceTitle(), i.sourceUrl(), i.sourceLocator(), 0);
    }
    private View view(String questionId, String versionId) {
        Question q = store.require(questionId, versionId);
        QuestionReviewState s = states.findById(versionId).orElse(null);
        return new View(UUID.fromString(questionId), UUID.fromString(versionId), s == null ? "unreviewed" : s.status,
            new Input(Subject.valueOf(q.subject), Difficulty.valueOf(q.difficulty), UUID.fromString(q.knowledgePointId),
                q.knowledgePointName, q.stem, CatalogService.options(q), q.correctOption, q.explanation,
                q.hintConcept, q.hintApproach, q.sourceTitle, q.sourceUrl, q.sourceLocator,
                s == null ? null : s.license, s == null ? null : s.retrievedAt,
                s == null ? null : Origin.valueOf(s.questionOrigin), s == null ? null : s.examYear, null),
            s == null ? null : s.createdBy, s == null ? null : s.createdAt,
            s == null ? null : s.reviewedBy, s == null ? null : s.reviewedAt, s == null ? null : s.reviewComment);
    }
    private String latest(Question q) { return editorials.findById(q.id).map(e -> e.latestVersionId).orElse(q.versionId); }
    private Question require(String id) { return questions.findById(id).orElseThrow(() -> missing()); }
    private Question editing(String id) { return questions.findForEditing(id).orElseThrow(() -> missing()); }
    private void admin(String actor) {
        if (accounts.findById(actor).filter(a -> "admin".equals(a.role)).isEmpty())
            throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
    }
    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "题目不存在"); }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_INPUT", message); }
}
