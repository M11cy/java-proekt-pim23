package ru.exam.infrastructure;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import ru.exam.domain.*;
import ru.exam.repository.CatalogRepository;
import ru.exam.service.AccessControl;

public final class JdbcCatalogRepository implements CatalogRepository {
  private final Database database;
  private final AccessControl access;
  private static final String CURRENT =
      "SELECT"
          + " q.id,r.topic_id,r.text,r.type,r.difficulty,r.importance,r.max_points_scaled,r.guidance,q.archived,q.current_revision"
          + " FROM questions q JOIN question_revisions r ON r.question_id=q.id AND"
          + " r.revision_no=q.current_revision ";

  public JdbcCatalogRepository(Database database, AccessControl access) {
    this.database = database;
    this.access = access;
  }

  @Override
  public List<Subject> subjects() {
    return database.read(
        c -> {
          var user = access.require(c);
          return JdbcSupport.list(
              c,
              "SELECT id,code,name FROM subjects WHERE ?='ADMIN' OR EXISTS(SELECT 1 FROM"
                  + " teaching_assignments a JOIN study_groups g ON g.id=a.group_id JOIN"
                  + " study_periods p ON p.id=a.period_id WHERE a.teacher_id=? AND"
                  + " a.subject_id=subjects.id AND a.active=1 AND g.active=1 AND p.active=1) ORDER"
                  + " BY code,id",
              r -> new Subject(r.getLong(1), r.getString(2), r.getString(3)),
              user.role().name(),
              user.id());
        });
  }

  @Override
  public Subject saveSubject(Subject value) {
    return database.transaction(
        c -> {
          access.admin(c);
          String before =
              value.id() == 0
                  ? "Новая дисциплина"
                  : JdbcSupport.list(
                          c,
                          "SELECT code,name FROM subjects WHERE id=?",
                          r -> r.getString(1) + ": " + r.getString(2),
                          value.id())
                      .stream()
                      .findFirst()
                      .orElse("");
          ensureUniqueCode(c, "subjects", value.id(), 0, value.code());
          String sql =
              value.id() == 0
                  ? "INSERT INTO subjects(code,name) VALUES(?,?)"
                  : "UPDATE subjects SET code=?,name=? WHERE id=?";
          try (var s = c.prepareStatement(sql)) {
            s.setString(1, value.code());
            s.setString(2, value.name());
            if (value.id() > 0) s.setLong(3, value.id());
            if (s.executeUpdate() != 1) throw new ValidationException("Дисциплина не найдена.");
          }
          long id = value.id() == 0 ? lastId(c) : value.id();
          if (value.id() == 0)
            JdbcSupport.execute(
                c,
                "INSERT INTO"
                    + " ranking_profiles(subject_id,name,version,formula,target,weight_i,weight_f,weight_d,weight_a,weight_e,horizon,minimum_samples,warning_threshold,saved_at)"
                    + " VALUES(?,'Основной',1,'rank-v1',3,0.30,0.25,0.20,0.15,0.10,365,10,1.5,?)",
                id,
                Instant.now().toString());
          audit(
              c,
              "SAVE_SUBJECT",
              "subject",
              id,
              before + " -> " + value.code() + ": " + value.name());
          return new Subject(id, value.code(), value.name());
        });
  }

  @Override
  public List<Topic> topics(long subjectId) {
    return database.read(
        c -> {
          access.subject(c, subjectId);
          var result = new ArrayList<Topic>();
          try (var s =
              c.prepareStatement("SELECT * FROM topics WHERE subject_id=? ORDER BY code,id")) {
            s.setLong(1, subjectId);
            try (var r = s.executeQuery()) {
              while (r.next())
                result.add(
                    new Topic(
                        r.getLong("id"), subjectId, r.getString("code"), r.getString("name")));
            }
          }
          return List.copyOf(result);
        });
  }

  @Override
  public Topic saveTopic(Topic value) {
    return database.transaction(
        c -> {
          access.subject(c, value.subjectId());
          if (value.id() > 0) access.topic(c, value.id());
          String before =
              value.id() == 0
                  ? "Новая тема"
                  : JdbcSupport.list(
                          c,
                          "SELECT code,name FROM topics WHERE id=?",
                          r -> r.getString(1) + ": " + r.getString(2),
                          value.id())
                      .stream()
                      .findFirst()
                      .orElse("");
          try (var s = c.prepareStatement("SELECT id FROM subjects WHERE id=?")) {
            s.setLong(1, value.subjectId());
            try (var r = s.executeQuery()) {
              if (!r.next()) throw new ValidationException("Дисциплина не найдена.");
            }
          }
          ensureUniqueCode(c, "topics", value.id(), value.subjectId(), value.code());
          String sql =
              value.id() == 0
                  ? "INSERT INTO topics(code,name,subject_id) VALUES(?,?,?)"
                  : "UPDATE topics SET code=?,name=? WHERE subject_id=? AND id=?";
          try (var s = c.prepareStatement(sql)) {
            s.setString(1, value.code());
            s.setString(2, value.name());
            s.setLong(3, value.subjectId());
            if (value.id() > 0) s.setLong(4, value.id());
            if (s.executeUpdate() != 1) throw new ValidationException("Тема не найдена.");
          }
          long id = value.id() == 0 ? lastId(c) : value.id();
          audit(c, "SAVE_TOPIC", "topic", id, before + " -> " + value.code() + ": " + value.name());
          return new Topic(id, value.subjectId(), value.code(), value.name());
        });
  }

  private void ensureUniqueCode(Connection c, String table, long id, long subjectId, String code)
      throws SQLException {
    // table выбирается только из двух констант выше, не из пользовательского ввода.
    String sql =
        "SELECT id FROM "
            + table
            + " WHERE code=? AND id<>?"
            + (table.equals("topics") ? " AND subject_id=?" : "");
    try (var s = c.prepareStatement(sql)) {
      s.setString(1, code);
      s.setLong(2, id);
      if (table.equals("topics")) s.setLong(3, subjectId);
      try (var r = s.executeQuery()) {
        if (r.next()) throw new ValidationException("Такой код уже существует.");
      }
    }
  }

  @Override
  public List<Question> questions(long subjectId) {
    return database.read(
        c -> {
          access.subject(c, subjectId);
          var list = new ArrayList<Question>();
          try (var s =
              c.prepareStatement(
                  CURRENT
                      + "JOIN topics t ON t.id=q.topic_id WHERE t.subject_id=? ORDER BY q.id")) {
            s.setLong(1, subjectId);
            try (var r = s.executeQuery()) {
              while (r.next()) list.add(map(r));
            }
          }
          return List.copyOf(list);
        });
  }

  private Question find(Connection c, long id) throws SQLException {
    try (var s = c.prepareStatement(CURRENT + "WHERE q.id=?")) {
      s.setLong(1, id);
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ValidationException("Вопрос не найден.");
        return map(r);
      }
    }
  }

  private Question map(ResultSet r) throws SQLException {
    return new Question(
        r.getLong(1),
        r.getLong(2),
        r.getString(3),
        QuestionType.valueOf(r.getString(4)),
        r.getInt(5),
        r.getInt(6),
        BigDecimal.valueOf(r.getLong(7), 2),
        r.getString(8),
        r.getInt(9) == 1,
        r.getInt(10));
  }

  @Override
  public List<Question> saveQuestions(List<Question> values) {
    return database.transaction(
        c -> {
          var result = new ArrayList<Question>();
          for (var value : values) result.add(save(c, value));
          return List.copyOf(result);
        });
  }

  private Question save(Connection c, Question value) throws SQLException {
    access.topic(c, value.topicId());
    Question previous = null;
    long subjectId;
    try (var s = c.prepareStatement("SELECT subject_id FROM topics WHERE id=?")) {
      s.setLong(1, value.topicId());
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ValidationException("Тема не найдена.");
        subjectId = r.getLong(1);
      }
    }
    if (value.id() > 0) {
      access.question(c, value.id());
      var current = find(c, value.id());
      previous = current;
      if (current.revision() != value.revision())
        throw new ValidationException("Вопрос уже изменён. Обновите список и откройте его заново.");
    }
    String duplicates =
        "SELECT q.id FROM questions q JOIN topics t ON t.id=q.topic_id JOIN question_revisions r ON"
            + " r.question_id=q.id AND r.revision_no=q.current_revision WHERE t.subject_id=? AND"
            + " r.normalized_text=? AND q.id<>?";
    try (var s = c.prepareStatement(duplicates)) {
      s.setLong(1, subjectId);
      s.setString(2, value.normalizedText());
      s.setLong(3, value.id());
      try (var r = s.executeQuery()) {
        if (r.next())
          throw new ValidationException(
              "Такой вопрос уже есть в дисциплине, включая архив. ID: " + r.getLong(1));
      }
    }
    long id = value.id();
    int revision = value.revision() + 1;
    if (id == 0) {
      if (value.revision() != 0)
        throw new ValidationException("Новый вопрос не должен иметь историю версий.");
      try (var s =
          c.prepareStatement(
              "INSERT INTO questions(topic_id,current_revision,archived) VALUES(?,?,?)")) {
        s.setLong(1, value.topicId());
        s.setInt(2, revision);
        s.setInt(3, value.archived() ? 1 : 0);
        s.executeUpdate();
      }
      id = lastId(c);
    } else {
      try (var s =
          c.prepareStatement(
              "UPDATE questions SET topic_id=?,current_revision=?,archived=? WHERE id=? AND"
                  + " current_revision=?")) {
        s.setLong(1, value.topicId());
        s.setInt(2, revision);
        s.setInt(3, value.archived() ? 1 : 0);
        s.setLong(4, id);
        s.setInt(5, value.revision());
        if (s.executeUpdate() != 1) throw new ValidationException("Конфликт версии вопроса.");
      }
    }
    if (previous == null || !sameContent(previous, value))
      JdbcSupport.execute(
          c,
          "UPDATE questions SET reviewed_at=? WHERE id=?",
          LocalDate.now(ZoneOffset.UTC).toString(),
          id);
    try (var s =
        c.prepareStatement("INSERT INTO question_revisions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
      s.setLong(1, id);
      s.setInt(2, revision);
      s.setLong(3, value.topicId());
      s.setString(4, value.text());
      s.setString(5, value.normalizedText());
      s.setString(6, value.type().name());
      s.setInt(7, value.difficulty());
      s.setInt(8, value.importance());
      s.setLong(9, value.maxPoints().movePointRight(2).longValueExact());
      s.setString(10, value.guidance());
      s.setInt(11, value.archived() ? 1 : 0);
      s.setString(12, Instant.now().toString());
      s.executeUpdate();
    }
    audit(
        c,
        value.id() == 0 ? "CREATE_QUESTION" : "REVISE_QUESTION",
        "question",
        id,
        (previous == null ? "Новый вопрос" : describe(previous))
            + " -> "
            + describe(value.identified(id, revision)));
    return value.identified(id, revision);
  }

  @Override
  public List<Revision> history(long id) {
    return database.read(
        c -> {
          access.question(c, id);
          var result = new ArrayList<Revision>();
          String sql =
              "SELECT"
                  + " question_id,topic_id,text,type,difficulty,importance,max_points_scaled,guidance,archived,revision_no,saved_at"
                  + " FROM question_revisions WHERE question_id=? ORDER BY revision_no";
          try (var s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
              while (r.next()) result.add(new Revision(map(r), r.getString(11)));
            }
          }
          return List.copyOf(result);
        });
  }

  @Override
  public List<AuditEntry> audit(AuditFilter filter) {
    return database.read(
        c -> {
          var user = access.require(c);
          String from =
              filter.from() == null
                  ? ""
                  : filter.from().atStartOfDay(ZoneId.systemDefault()).toInstant().toString();
          String until =
              filter.until() == null
                  ? "9999"
                  : filter
                      .until()
                      .plusDays(1)
                      .atStartOfDay(ZoneId.systemDefault())
                      .toInstant()
                      .toString();
          return JdbcSupport.list(
              c,
              "SELECT occurred_at,actor,action,object_type,object_id,result,details FROM audit_log"
                  + " l WHERE occurred_at>=? AND occurred_at<? AND (?='' OR action=?) AND (?='' OR"
                  + " object_type=?) AND (?='' OR instr(lower(details||' '||actor),lower(?))>0) AND"
                  + " (?='ADMIN' OR actor=? OR EXISTS(SELECT 1 FROM teaching_assignments a JOIN"
                  + " study_groups g ON g.id=a.group_id JOIN study_periods p ON p.id=a.period_id"
                  + " WHERE a.subject_id=l.subject_id AND a.teacher_id=? AND a.active=1 AND"
                  + " g.active=1 AND p.active=1)) ORDER BY id DESC LIMIT 5000",
              r ->
                  new AuditEntry(
                      r.getString(1),
                      r.getString(2),
                      r.getString(3),
                      r.getString(4),
                      r.getLong(5),
                      r.getString(6),
                      r.getString(7)),
              from,
              until,
              filter.action(),
              filter.action(),
              filter.objectType(),
              filter.objectType(),
              filter.text(),
              filter.text(),
              user.role().name(),
              user.login(),
              user.id());
        });
  }

  private void audit(Connection c, String action, String type, long id, String details)
      throws SQLException {
    Long subjectId =
        switch (type) {
          case "subject" -> id;
          case "topic" -> access.topic(c, id);
          case "question" -> access.question(c, id);
          default -> null;
        };
    AuditLog.write(c, access.require(c).login(), action, type, id, subjectId, "SUCCESS", details);
  }

  private boolean sameContent(Question a, Question b) {
    return a.topicId() == b.topicId()
        && a.text().equals(b.text())
        && a.guidance().equals(b.guidance())
        && a.type() == b.type()
        && a.difficulty() == b.difficulty()
        && a.importance() == b.importance()
        && a.maxPoints().equals(b.maxPoints());
  }

  private String describe(Question q) {
    return "v"
        + q.revision()
        + "; тема="
        + q.topicId()
        + "; тип="
        + q.type()
        + "; сложность="
        + q.difficulty()
        + "; значимость="
        + q.importance()
        + "; баллы="
        + q.maxPoints()
        + "; архив="
        + q.archived()
        + "; текст="
        + q.text()
        + "; критерий="
        + q.guidance();
  }

  private long lastId(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT last_insert_rowid()")) {
      r.next();
      return r.getLong(1);
    }
  }
}
