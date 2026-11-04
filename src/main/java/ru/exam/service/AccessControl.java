package ru.exam.service;

import java.sql.*;
import ru.exam.domain.*;
import ru.exam.infrastructure.Database;

public final class AccessControl {
  private final Database database;
  private final Session session;

  public AccessControl(Database database, Session session) {
    this.database = database;
    this.session = session;
  }

  public User current() {
    return database.read(this::require);
  }

  public User admin() {
    return database.read(this::admin);
  }

  public void subject(long id) {
    database.read(
        c -> {
          subject(c, id);
          return null;
        });
  }

  public void topic(long id) {
    database.read(
        c -> {
          topic(c, id);
          return null;
        });
  }

  public void question(long id) {
    database.read(
        c -> {
          question(c, id);
          return null;
        });
  }

  public User require(Connection c) throws SQLException {
    var login = session.user();
    if (login == null) throw new ValidationException("Войдите в систему.");
    try (var s =
        c.prepareStatement(
            "SELECT id,login,name,role,active,password_version FROM users WHERE id=?")) {
      s.setLong(1, login.id());
      try (var r = s.executeQuery()) {
        if (!r.next() || !r.getBoolean(5) || r.getInt(6) != login.passwordVersion())
          throw new ValidationException("Сеанс завершён. Войдите снова.");
        return map(r);
      }
    }
  }

  public User admin(Connection c) throws SQLException {
    var user = require(c);
    if (user.role() != User.Role.ADMIN)
      throw new ValidationException("Действие доступно администратору.");
    return user;
  }

  public void subject(Connection c, long id) throws SQLException {
    var user = require(c);
    String sql =
        "SELECT id FROM subjects WHERE id=? AND (?='ADMIN' OR EXISTS(SELECT 1 FROM"
            + " teaching_assignments a JOIN study_groups g ON g.id=a.group_id JOIN study_periods p"
            + " ON p.id=a.period_id WHERE a.subject_id=subjects.id AND a.teacher_id=? AND"
            + " a.active=1 AND g.active=1 AND p.active=1))";
    try (var s = c.prepareStatement(sql)) {
      s.setLong(1, id);
      s.setString(2, user.role().name());
      s.setLong(3, user.id());
      try (var r = s.executeQuery()) {
        if (!r.next())
          throw new ValidationException(
              "Дисциплина недоступна. Проверьте назначение преподавателя.");
      }
    }
  }

  public long topic(Connection c, long id) throws SQLException {
    try (var s = c.prepareStatement("SELECT subject_id FROM topics WHERE id=?")) {
      s.setLong(1, id);
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ValidationException("Тема не найдена.");
        long subjectId = r.getLong(1);
        subject(c, subjectId);
        return subjectId;
      }
    }
  }

  public long question(Connection c, long id) throws SQLException {
    try (var s = c.prepareStatement("SELECT topic_id FROM questions WHERE id=?")) {
      s.setLong(1, id);
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ValidationException("Вопрос не найден.");
        return topic(c, r.getLong(1));
      }
    }
  }

  public static User map(ResultSet r) throws SQLException {
    return new User(
        r.getLong(1),
        r.getString(2),
        r.getString(3),
        User.Role.valueOf(r.getString(4)),
        r.getBoolean(5),
        r.getInt(6));
  }
}
