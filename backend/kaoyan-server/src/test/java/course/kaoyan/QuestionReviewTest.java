package course.kaoyan;

import com.fasterxml.jackson.databind.*;
import course.kaoyan.auth.AccountRepository;
import course.kaoyan.catalog.*;
import course.kaoyan.admin.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:question-review-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "agent.internal-key=test-internal-key-123456789"})
@AutoConfigureMockMvc
@Transactional
class QuestionReviewTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired QuestionRepository questions;
    @Autowired QuestionVersionStore snapshots;
    @Autowired QuestionReviewStateRepository states;
    @Autowired QuestionReviewEventRepository events;

    private String login(boolean admin) throws Exception {
        String name = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        JsonNode result = call(post("/api/auth/register"), null, Map.of("username", name, "password", "test-password"), 200);
        if (admin) { var account = accounts.findByUsername(name).orElseThrow(); account.role = "admin"; accounts.saveAndFlush(account); }
        return result.path("token").asText();
    }
    private JsonNode call(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
    }
    private Map<String,Object> input() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("subject", "data_structure"); data.put("difficulty", "easy");
        data.put("knowledgePointId", UUID.randomUUID().toString()); data.put("knowledgePointName", "审核测试");
        data.put("stem", "待审核题干"); data.put("options", Map.of("A", "甲", "B", "乙", "C", "丙", "D", "丁"));
        data.put("correctOption", "B"); data.put("explanation", "解析");
        data.put("sourceTitle", "自编测试题"); data.put("sourceUrl", "/references/demo-408.html#review");
        data.put("sourceLocator", "第1题"); data.put("license", "测试授权");
        data.put("retrievedAt", LocalDate.now().toString()); data.put("questionOrigin", "self_authored");
        return data;
    }
    private JsonNode create(String token) throws Exception { return call(post("/api/admin/questions"), token, input(), 200); }
    private String path(JsonNode view) { return "/api/admin/questions/" + view.path("questionId").asText(); }
    private Map<String,Object> decision(JsonNode view, String status) {
        return Map.of("questionVersionId", view.path("questionVersionId").asText(), "status", status, "comment", "内容核对测试");
    }
    @Test void allQuestionRoutesRequireRealAdmin() throws Exception {
        String student = login(false), id = UUID.randomUUID().toString();
        for (String token : Arrays.asList(null, student)) {
            int expected = token == null ? 401 : 403;
            for (String suffix : List.of("", "/"+id, "/"+id+"/versions", "/"+id+"/reviews"))
                call(get("/api/admin/questions"+suffix), token, null, expected);
            call(post("/api/admin/questions"), token, input(), expected);
            call(put("/api/admin/questions/"+id), token, input(), expected);
            call(post("/api/admin/questions/"+id+"/review"), token, Map.of(), expected);
        }
    }
    @Test void validationRejectsMalformedAndForgedFieldsWithoutWrites() throws Exception {
        String admin = login(true); long before = questions.count(), beforeStates = states.count();
        List<Map<String,Object>> invalid = new ArrayList<>();
        for (var entry : Map.<String,Object>of("license", " ", "stem", "x".repeat(2001),
                "sourceUrl", "javascript:alert(1)", "correctOption", "Z", "options", Map.of("A", "only"),
                "retrievedAt", LocalDate.now().plusDays(1).toString(), "examYear", 2025,
                "createdBy", "forged", "status", "approved", "expectedVersionId", UUID.randomUUID()).entrySet()) {
            var data = input(); data.put(entry.getKey(), entry.getValue()); invalid.add(data);
        }
        var past = input(); past.put("questionOrigin", "authorized_past"); invalid.add(past);
        for (var data : invalid) call(post("/api/admin/questions"), admin, data, 400);
        assertEquals(before, questions.count()); assertEquals(beforeStates, states.count());
        call(get("/api/admin/questions?page=-1"), admin, null, 400);
    }
    @Test void approvalRecordsActorButNeverPublishes() throws Exception {
        String admin = login(true), reviewer = login(true), student = login(false); JsonNode draft = create(admin);
        String reviewerId = call(get("/api/auth/me"), reviewer, null, 200).path("id").asText();
        String id = draft.path("questionId").asText();
        assertEquals("draft", draft.path("status").asText());
        for (int step=0; step<2; step++) {
            assertFalse(questions.findById(id).orElseThrow().active);
            call(get("/api/questions/"+id), student, null, 404);
            call(put("/api/favorites/"+id), student, null, 404);
            var list = call(get("/api/questions?knowledgePointId="+draft.path("content").path("knowledgePointId").asText()), student, null, 200);
            assertEquals(0, list.path("totalItems").asInt());
            call(post("/api/practice-sessions"), student, Map.of("mode", "chapter", "questionCount", 1,
                "knowledgePointId", draft.path("content").path("knowledgePointId").asText()), 400);
            if (step == 0) {
                JsonNode approved = call(post(path(draft)+"/review"), reviewer, decision(draft, "approved"), 200);
                assertEquals("approved", approved.path("status").asText());
                assertEquals(reviewerId, approved.path("reviewedBy").asText());
                assertFalse(approved.path("reviewedAt").isNull());
            }
        }
        call(post(path(draft)+"/review"), admin, decision(draft, "approved"), 409);
        JsonNode history = call(get(path(draft)+"/reviews"), admin, null, 200);
        assertEquals(1, history.size()); assertEquals(draft.path("questionVersionId"), history.get(0).path("questionVersionId"));
        assertEquals(reviewerId, history.get(0).path("reviewerId").asText());
    }
    @Test void rejectionRevisionAndStaleRequestsPreserveEveryVersion() throws Exception {
        String admin = login(true); JsonNode original = create(admin);
        call(post(path(original)+"/review"), admin, decision(original, "rejected"), 200);
        var data = input(); data.put("expectedVersionId", original.path("questionVersionId").asText()); data.put("stem", "修订题干");
        JsonNode revised = call(put(path(original)), admin, data, 200);
        assertEquals("draft", revised.path("status").asText()); assertNotEquals(original.path("questionVersionId"), revised.path("questionVersionId"));
        call(put(path(original)), admin, data, 409);
        call(post(path(original)+"/review"), admin, decision(original, "approved"), 409);
        JsonNode another = create(admin);
        call(post(path(original)+"/review"), admin, decision(another, "approved"), 409);
        JsonNode history = call(get(path(original)+"/versions"), admin, null, 200);
        assertEquals(2, history.size());
        assertEquals("待审核题干", snapshots.require(original.path("questionId").asText(), original.path("questionVersionId").asText()).stem);
        assertEquals(1, events.findByQuestionIdOrderByCreatedAtAscIdAsc(original.path("questionId").asText()).size());
        call(put(path(original)), admin, input(), 400);
    }
    @Test void editingDemoKeepsPlayableProjectionAndBoundSession() throws Exception {
        String admin = login(true), student = login(false); var q = questions.findByActiveTrueOrderByStableOrderAsc().get(0);
        String version = q.versionId, stem = q.stem, answer = q.correctOption;
        JsonNode demo = call(get("/api/admin/questions/"+q.id), admin, null, 200);
        assertEquals("unreviewed", demo.path("status").asText());
        call(post(path(demo)+"/review"), admin, decision(demo,"approved"),409);
        JsonNode session = call(post("/api/practice-sessions"), student, Map.of("mode","chapter","questionCount",1,"knowledgePointId",q.knowledgePointId),200);
        var data = input(); data.put("expectedVersionId",version); data.put("stem","新题干");
        JsonNode draft = call(put(path(demo)),admin,data,200);
        call(post(path(draft)+"/review"),admin,decision(draft,"approved"),200);
        assertEquals(version,questions.findById(q.id).orElseThrow().versionId);
        assertEquals(stem, call(get("/api/questions/"+q.id),student,null,200).path("stem").asText());
        JsonNode item = session.path("items").get(0);
        JsonNode result = call(post("/api/attempts"),student,Map.of("questionId",q.id,"questionVersionId",version,
            "selectedOption",answer,"clientRequestId",UUID.randomUUID(),"sessionId",session.path("id").asText(),"sessionItemId",item.path("id").asText()),200);
        assertTrue(result.path("correct").asBoolean());
    }
    @Test void unrelatedAdminRoutesRemainUnimplemented() throws Exception {
        String admin = login(true);
        call(get("/api/admin/documents"),admin,null,501);
        call(get("/api/admin/admissions"),admin,null,501);
        call(post("/api/admin/releases"),admin,null,501);
    }
    @Test void invalidReviewPayloadsNeverCreateAudit() throws Exception {
        String admin = login(true); JsonNode draft = create(admin);
        for (var entry : Map.<String,Object>of("reviewerId","forged","status","draft","comment"," ","questionVersionId","invalid").entrySet()) {
            var body = new LinkedHashMap<>(decision(draft,"approved")); body.put(entry.getKey(),entry.getValue());
            call(post(path(draft)+"/review"),admin,body,400);
        }
        assertEquals(0,events.findByQuestionIdOrderByCreatedAtAscIdAsc(draft.path("questionId").asText()).size());
        assertEquals("draft",call(get(path(draft)),admin,null,200).path("status").asText());
        call(get("/api/admin/questions/"+UUID.randomUUID()),admin,null,404);
    }
    private void race(String admin, String url, Object body, boolean review) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Integer> action = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform((review ? post(url) : put(url)).header("Authorization", "Bearer "+admin)
                    .contentType("application/json").content(mapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
            };
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            var results = new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS), b.get(20,TimeUnit.SECONDS)));
            Collections.sort(results); assertEquals(List.of(200,409),results);
        } finally { pool.shutdownNow(); }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentReviewsHaveExactlyOneWinnerAndAudit() throws Exception {
        String admin = login(true); JsonNode draft = create(admin);
        race(admin,path(draft)+"/review",decision(draft,"approved"),true);
        assertEquals(1, events.findByQuestionIdOrderByCreatedAtAscIdAsc(draft.path("questionId").asText()).size());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentRevisionsCannotLoseUpdates() throws Exception {
        String admin = login(true); JsonNode draft = create(admin);
        var data = input(); data.put("expectedVersionId", draft.path("questionVersionId").asText());
        race(admin,path(draft),data,false);
        assertEquals(2,call(get(path(draft)+"/versions"),admin,null,200).size());
    }
}
