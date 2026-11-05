package ru.exam.service;

import static ru.exam.infrastructure.JdbcSupport.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import ru.exam.domain.*;
import ru.exam.infrastructure.*;

public final class RankingService {
  private final Database database;
  private final AccessControl access;
  private final CatalogService catalog;
  private final QuestionRanker ranker = new QuestionRanker();

  public RankingService(Database database, AccessControl access, CatalogService catalog) {
    this.database = database;
    this.access = access;
    this.catalog = catalog;
  }

  public List<RankingProfile> profiles(long subjectId) {
    return database.read(
        c -> {
          access.subject(c, subjectId);
          return list(
              c,
              "SELECT"
                  + " id,subject_id,name,version,target,weight_i,weight_f,weight_d,weight_a,weight_e,horizon,minimum_samples,warning_threshold"
                  + " FROM ranking_profiles WHERE subject_id=? ORDER BY name,version DESC",
              this::profile,
              subjectId);
        });
  }

  public RankingProfile saveProfile(RankingProfile value) {
    return database.transaction(
        c -> {
          var user = access.require(c);
          access.subject(c, value.subjectId());
          var existing =
              list(
                  c,
                  "SELECT coalesce(max(version),0) FROM ranking_profiles WHERE subject_id=? AND"
                      + " name=?",
                  r -> r.getInt(1),
                  value.subjectId(),
                  value.name());
          int last = existing.getFirst();
          if (value.version() != last)
            throw new ValidationException(
                "Профиль уже изменён или имя занято. Загрузите последнюю версию либо задайте новое"
                    + " имя.");
          var w = value.weights();
          int version = last + 1;
          execute(
              c,
              "INSERT INTO"
                  + " ranking_profiles(subject_id,name,version,formula,target,weight_i,weight_f,weight_d,weight_a,weight_e,horizon,minimum_samples,warning_threshold,saved_at)"
                  + " VALUES(?,?,?,'rank-v1',?,?,?,?,?,?,?,?,?,?)",
              value.subjectId(),
              value.name(),
              version,
              value.target(),
              w.get(0),
              w.get(1),
              w.get(2),
              w.get(3),
              w.get(4),
              value.horizon(),
              value.minimumSamples(),
              value.warningThreshold(),
              Instant.now().toString());
          long id = lastId(c);
          AuditLog.write(
              c,
              user.login(),
              "SAVE_RANKING_PROFILE",
              "ranking_profile",
              id,
              value.subjectId(),
              "SUCCESS",
              value.name()
                  + " v"
                  + version
                  + "; rank-v1; цель="
                  + value.target()
                  + "; веса="
                  + w
                  + "; период="
                  + value.horizon()
                  + "; минимум="
                  + value.minimumSamples()
                  + "; порог="
                  + value.warningThreshold());
          return new RankingProfile(
              id,
              value.subjectId(),
              value.name(),
              version,
              value.target(),
              w,
              value.horizon(),
              value.minimumSamples(),
              value.warningThreshold());
        });
  }

  public record BankRank(Integer difficulty, boolean old) {}

  public Map<Long, BankRank> bankRanks(long subjectId) {
    return database.read(c -> {
      access.subject(c, subjectId);
      var ranks = new HashMap<Long, BankRank>();
      try (var statement = c.prepareStatement("""
          SELECT q.id,
                 CASE WHEN r.revision=q.current_revision AND q.archived=0 THEN r.difficulty END,
                 julianday(?) - julianday(q.reviewed_at) >= coalesce(p.horizon,365)
          FROM questions q JOIN topics t ON t.id=q.topic_id
          LEFT JOIN question_rankings r ON r.question_id=q.id
          LEFT JOIN ranking_profiles p ON p.id=r.profile_id AND p.subject_id=t.subject_id
          WHERE t.subject_id=?
          """)) {
        statement.setString(1, LocalDate.now(ZoneOffset.UTC).toString());
        statement.setLong(2, subjectId);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            int value = rows.getInt(2);
            Integer difficulty = rows.wasNull() ? null : value;
            ranks.put(rows.getLong(1), new BankRank(difficulty, rows.getBoolean(3)));
          }
        }
      }
      return Map.copyOf(ranks);
    });
  }

  public List<QuestionRanker.Result> calculate(long subjectId, long profileId) {
    synchronized (database) {
      var selected = profiles(subjectId).stream()
          .filter(p -> p.id() == profileId)
          .findFirst()
          .orElseThrow(() -> new ValidationException("Профиль не найден."));
      var questions = catalog.questions(subjectId).stream().filter(q -> !q.archived()).toList();
      var date = LocalDate.now(ZoneOffset.UTC);
      return database.transaction(c -> {
        var actor = access.require(c);
        access.subject(c, subjectId);
        var results = new ArrayList<QuestionRanker.Result>();
        try (var save = c.prepareStatement("""
            INSERT INTO question_rankings(question_id,revision,profile_id,calculated_on,score,difficulty)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(question_id) DO UPDATE SET revision=excluded.revision,
              profile_id=excluded.profile_id, calculated_on=excluded.calculated_on,
              score=excluded.score, difficulty=excluded.difficulty
            """)) {
          for (var question : questions) {
            var result = list(c, """
                SELECT q.reviewed_at,
                       (SELECT count(*) FROM ranking_word_uses w WHERE w.question_id=q.id),
                       count(e.discrepancy), avg(e.discrepancy)
                FROM questions q LEFT JOIN ranking_exam_discrepancies e
                  ON e.question_id=q.id AND e.revision=q.current_revision
                WHERE q.id=? GROUP BY q.id
                """, row -> ranker.calculate(question, selected, row.getLong(2), row.getLong(3),
                    (Double) row.getObject(4), LocalDate.parse(row.getString(1)), date),
                question.id()).getFirst();
            results.add(result);
            save.setLong(1, question.id());
            save.setInt(2, question.revision());
            save.setLong(3, profileId);
            save.setString(4, date.toString());
            save.setDouble(5, result.score());
            save.setInt(6, result.difficulty());
            save.addBatch();
          }
          save.executeBatch();
        }
        results.sort(Comparator.comparingDouble(QuestionRanker.Result::score).reversed()
            .thenComparingLong(r -> r.question().id()));
        AuditLog.write(c, actor.login(), "CALCULATE_RANKING", "ranking_profile", profileId,
            subjectId, "SUCCESS", selected + ", " + QuestionRanker.FORMULA
                + ", сохранено вопросов: " + results.size());
        return List.copyOf(results);
      });
    }
  }

  /** Вызывается после успешного создания документа. Ключ документа должен быть стабильным. */
  public void recordWordUsage(long subjectId, String documentKey, List<Long> questionIds) {
    String key = Organization.text(documentKey, "Ключ документа", 200);
    var ids = Set.copyOf(questionIds);
    if (ids.isEmpty()) throw new ValidationException("В документе нет вопросов.");
    database.transaction(c -> {
      access.subject(c, subjectId);
      for (long id : ids) {
        if (access.question(c, id) != subjectId)
          throw new ValidationException("Вопрос относится к другой дисциплине.");
        execute(c, """
            INSERT INTO ranking_word_uses(document_key,question_id) VALUES(?,?)
            ON CONFLICT(document_key,question_id) DO NOTHING
            """, key, id);
      }
      return null;
    });
  }

  /** Сохраняет оценку преподавателя от 0 до 4 для ответа на конкретную версию вопроса. */
  public void recordExamDiscrepancy(String resultKey, long questionId, int revision, int discrepancy) {
    String key = Organization.text(resultKey, "Ключ результата экзамена", 200);
    if (revision < 1 || discrepancy < 0 || discrepancy > 4)
      throw new ValidationException("Укажите версию вопроса и расхождение от 0 до 4.");
    database.transaction(c -> {
      access.question(c, questionId);
      execute(c, """
          INSERT INTO ranking_exam_discrepancies(result_key,question_id,revision,discrepancy)
          VALUES(?,?,?,?) ON CONFLICT(result_key,question_id,revision)
          DO UPDATE SET discrepancy=excluded.discrepancy
          """, key, questionId, revision, discrepancy);
      return null;
    });
  }

  private RankingProfile profile(ResultSet r) throws SQLException {
    return new RankingProfile(
        r.getLong(1),
        r.getLong(2),
        r.getString(3),
        r.getInt(4),
        r.getInt(5),
        List.of(r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getDouble(10)),
        r.getInt(11),
        r.getInt(12),
        r.getDouble(13));
  }

}
