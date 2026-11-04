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

  public List<QuestionRanker.Result> calculate(
      long subjectId, long profileId, LocalDate date, boolean includeSynthetic) {
    if (date == null || date.isAfter(LocalDate.now(ZoneOffset.UTC)))
      throw new ValidationException("Дата расчёта не может быть в будущем.");
    synchronized (database) {
      var selected =
          profiles(subjectId).stream()
              .filter(p -> p.id() == profileId)
              .findFirst()
              .orElseThrow(() -> new ValidationException("Профиль не найден."));
      var questions = catalog.questions(subjectId).stream().filter(q -> !q.archived()).toList();
      return database.transaction(
          c -> {
            var actor = access.require(c);
            access.subject(c, subjectId);
            var result = new ArrayList<QuestionRanker.Result>();
            for (var q : questions) {
              var observations =
                  list(
                      c,
                      "SELECT"
                          + " event_key,question_id,revision,occurred_on,uses,attempts,ratio_sum,source,synthetic"
                          + " FROM ranking_observations WHERE question_id=? AND occurred_on>=? AND"
                          + " occurred_on<=? AND (?=1 OR synthetic=0)",
                      this::observation,
                      q.id(),
                      date.minusDays(selected.horizon()).toString(),
                      date.toString(),
                      includeSynthetic);
              long uses = 0, attempts = 0;
              double ratioSum = 0;
              boolean synthetic = false;
              for (var o : observations) {
                uses += o.uses();
                if (o.revision() == q.revision()) {
                  attempts += o.attempts();
                  ratioSum += o.ratioSum();
                }
                synthetic |= o.synthetic();
              }
              var reviewed =
                  list(
                          c,
                          "SELECT reviewed_at FROM questions WHERE id=?",
                          r -> LocalDate.parse(r.getString(1)),
                          q.id())
                      .getFirst();
              result.add(
                  ranker.calculate(
                      q,
                      selected,
                      new QuestionRanker.Statistics(uses, attempts, ratioSum, reviewed, synthetic),
                      date));
            }
            result.sort(
                Comparator.comparingDouble(QuestionRanker.Result::score)
                    .reversed()
                    .thenComparingLong(r -> r.question().id()));
            AuditLog.write(
                c,
                actor.login(),
                "CALCULATE_RANKING",
                "ranking_profile",
                profileId,
                subjectId,
                "SUCCESS",
                selected
                    + "; rank-v1; дата="
                    + date
                    + "; вопросов="
                    + result.size()
                    + "; включать синтетические="
                    + includeSynthetic);
            return List.copyOf(result);
          });
    }
  }

  public void recordObservation(RankingObservation value) {
    database.transaction(
        c -> {
          var actor = access.require(c);
          long subjectId = access.question(c, value.questionId());
          if (!list(
                  c,
                  "SELECT id FROM ranking_observations WHERE event_key=?",
                  r -> r.getLong(1),
                  value.eventKey())
              .isEmpty())
            throw new ValidationException("Наблюдение с таким ключом уже зарегистрировано.");
          execute(
              c,
              "INSERT INTO"
                  + " ranking_observations(event_key,question_id,revision,occurred_on,uses,attempts,ratio_sum,source,synthetic)"
                  + " VALUES(?,?,?,?,?,?,?,?,?)",
              value.eventKey(),
              value.questionId(),
              value.revision(),
              value.occurredOn().toString(),
              value.uses(),
              value.attempts(),
              value.ratioSum(),
              value.source(),
              value.synthetic());
          AuditLog.write(
              c,
              actor.login(),
              "ADD_OBSERVATION",
              "question",
              value.questionId(),
              subjectId,
              "SUCCESS",
              "v"
                  + value.revision()
                  + "; дата="
                  + value.occurredOn()
                  + "; источник="
                  + value.source()
                  + "; синтетические="
                  + value.synthetic()
                  + "; использований="
                  + value.uses()
                  + "; ответов="
                  + value.attempts()
                  + "; сумма долей="
                  + value.ratioSum());
          return null;
        });
  }

  public List<RankingObservation> observations(long questionId) {
    return database.read(
        c -> {
          access.question(c, questionId);
          return list(
              c,
              "SELECT"
                  + " event_key,question_id,revision,occurred_on,uses,attempts,ratio_sum,source,synthetic"
                  + " FROM ranking_observations WHERE question_id=? ORDER BY occurred_on,id",
              this::observation,
              questionId);
        });
  }

  public void confirmReview(long questionId) {
    database.transaction(
        c -> {
          var user = access.require(c);
          long subjectId = access.question(c, questionId);
          execute(
              c,
              "UPDATE questions SET reviewed_at=? WHERE id=?",
              LocalDate.now(ZoneOffset.UTC).toString(),
              questionId);
          AuditLog.write(
              c,
              user.login(),
              "REVIEW_QUESTION",
              "question",
              questionId,
              subjectId,
              "SUCCESS",
              "Подтверждён пересмотр актуальности вопроса");
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

  private RankingObservation observation(ResultSet r) throws SQLException {
    return new RankingObservation(
        r.getString(1),
        r.getLong(2),
        r.getInt(3),
        LocalDate.parse(r.getString(4)),
        r.getInt(5),
        r.getInt(6),
        r.getDouble(7),
        r.getString(8),
        r.getBoolean(9));
  }
}
