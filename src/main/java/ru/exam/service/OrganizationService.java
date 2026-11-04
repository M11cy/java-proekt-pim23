package ru.exam.service;

import static ru.exam.infrastructure.JdbcSupport.*;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import ru.exam.domain.*;
import ru.exam.domain.Organization.*;
import ru.exam.infrastructure.*;

public final class OrganizationService {
  public record Snapshot(
      List<Group> groups,
      List<Student> students,
      List<Period> periods,
      List<Assignment> assignments) {}

  private final Database database;
  private final AccessControl access;

  public OrganizationService(Database database, AccessControl access) {
    this.database = database;
    this.access = access;
  }

  public Snapshot snapshot() {
    return database.read(
        c -> {
          User user = access.require(c);
          boolean admin = user.role() == User.Role.ADMIN;
          String visible =
              "EXISTS(SELECT 1 FROM teaching_assignments a JOIN study_groups g ON g.id=a.group_id"
                  + " JOIN study_periods p ON p.id=a.period_id WHERE a.teacher_id=? AND a.active=1"
                  + " AND g.active=1 AND p.active=1 AND ";
          List<Group> groups =
              list(
                  c,
                  "SELECT id,code,name,active FROM study_groups"
                      + (admin
                          ? ""
                          : " WHERE " + visible + "a.group_id=study_groups.id) ORDER BY code"),
                  r -> new Group(r.getLong(1), r.getString(2), r.getString(3), r.getBoolean(4)),
                  admin ? new Object[0] : new Object[] {user.id()});
          List<Student> students =
              list(
                  c,
                  "SELECT id,group_id,number,name,active FROM students"
                      + (admin
                          ? ""
                          : " WHERE " + visible + "a.group_id=students.group_id) ORDER BY name"),
                  r ->
                      new Student(
                          r.getLong(1),
                          r.getLong(2),
                          r.getString(3),
                          r.getString(4),
                          r.getBoolean(5)),
                  admin ? new Object[0] : new Object[] {user.id()});
          List<Period> periods =
              list(
                  c,
                  "SELECT id,name,starts_on,ends_on,active FROM study_periods"
                      + (admin
                          ? ""
                          : " WHERE "
                              + visible
                              + "a.period_id=study_periods.id) ORDER BY starts_on"),
                  r ->
                      new Period(
                          r.getLong(1),
                          r.getString(2),
                          LocalDate.parse(r.getString(3)),
                          LocalDate.parse(r.getString(4)),
                          r.getBoolean(5)),
                  admin ? new Object[0] : new Object[] {user.id()});
          List<Assignment> assignments =
              list(
                  c,
                  "SELECT id,teacher_id,subject_id,group_id,period_id,active FROM"
                      + " teaching_assignments"
                      + (admin ? " ORDER BY id" : " WHERE teacher_id=? AND active=1 ORDER BY id"),
                  r ->
                      new Assignment(
                          r.getLong(1),
                          r.getLong(2),
                          r.getLong(3),
                          r.getLong(4),
                          r.getLong(5),
                          r.getBoolean(6)),
                  admin ? new Object[0] : new Object[] {user.id()});
          return new Snapshot(groups, students, periods, assignments);
        });
  }

  public Group saveGroup(Group value) {
    return database.transaction(
        c -> {
          var actor = access.admin(c);
          unique(c, "study_groups", "code", value.code(), value.id());
          String before = old(c, "study_groups", value.id());
          if (value.id() == 0)
            execute(
                c,
                "INSERT INTO study_groups(code,name,active) VALUES(?,?,?)",
                value.code(),
                value.name(),
                value.active());
          else
            changed(
                execute(
                    c,
                    "UPDATE study_groups SET code=?,name=?,active=? WHERE id=?",
                    value.code(),
                    value.name(),
                    value.active(),
                    value.id()));
          long id = value.id() == 0 ? lastId(c) : value.id();
          audit(
              c,
              actor,
              "group",
              id,
              before,
              value.toString() + ", активна=" + value.active(),
              null);
          return new Group(id, value.code(), value.name(), value.active());
        });
  }

  public Student saveStudent(Student value) {
    return database.transaction(
        c -> {
          var actor = access.admin(c);
          unique(c, "students", "number", value.number(), value.id());
          exists(c, "study_groups", value.groupId(), value.active());
          String before = old(c, "students", value.id());
          if (value.id() == 0)
            execute(
                c,
                "INSERT INTO students(group_id,number,name,active) VALUES(?,?,?,?)",
                value.groupId(),
                value.number(),
                value.name(),
                value.active());
          else
            changed(
                execute(
                    c,
                    "UPDATE students SET group_id=?,number=?,name=?,active=? WHERE id=?",
                    value.groupId(),
                    value.number(),
                    value.name(),
                    value.active(),
                    value.id()));
          long id = value.id() == 0 ? lastId(c) : value.id();
          audit(c, actor, "student", id, before, value.toString(), null);
          return new Student(id, value.groupId(), value.number(), value.name(), value.active());
        });
  }

  public Period savePeriod(Period value) {
    return database.transaction(
        c -> {
          var actor = access.admin(c);
          unique(c, "study_periods", "name", value.name(), value.id());
          String before = old(c, "study_periods", value.id());
          if (value.id() == 0)
            execute(
                c,
                "INSERT INTO study_periods(name,starts_on,ends_on,active) VALUES(?,?,?,?)",
                value.name(),
                value.startsOn().toString(),
                value.endsOn().toString(),
                value.active());
          else
            changed(
                execute(
                    c,
                    "UPDATE study_periods SET name=?,starts_on=?,ends_on=?,active=? WHERE id=?",
                    value.name(),
                    value.startsOn().toString(),
                    value.endsOn().toString(),
                    value.active(),
                    value.id()));
          long id = value.id() == 0 ? lastId(c) : value.id();
          audit(
              c,
              actor,
              "period",
              id,
              before,
              value.toString()
                  + " "
                  + value.startsOn()
                  + ".."
                  + value.endsOn()
                  + ", активен="
                  + value.active(),
              null);
          return new Period(id, value.name(), value.startsOn(), value.endsOn(), value.active());
        });
  }

  public Assignment saveAssignment(Assignment value) {
    return database.transaction(
        c -> {
          var actor = access.admin(c);
          exists(c, "users", value.teacherId(), value.active());
          if (value.active()) {
            var roles =
                list(
                    c, "SELECT role FROM users WHERE id=?", r -> r.getString(1), value.teacherId());
            if (!roles.getFirst().equals("TEACHER"))
              throw new ValidationException("Выберите пользователя с ролью преподавателя.");
          }
          exists(c, "study_groups", value.groupId(), value.active());
          exists(c, "study_periods", value.periodId(), value.active());
          var duplicates =
              list(
                  c,
                  "SELECT id FROM teaching_assignments WHERE teacher_id=? AND subject_id=? AND"
                      + " group_id=? AND period_id=? AND id<>?",
                  r -> r.getLong(1),
                  value.teacherId(),
                  value.subjectId(),
                  value.groupId(),
                  value.periodId(),
                  value.id());
          if (!duplicates.isEmpty())
            throw new ValidationException(
                "Такое назначение уже существует. Измените его состояние.");
          String before = old(c, "teaching_assignments", value.id());
          if (value.id() == 0)
            execute(
                c,
                "INSERT INTO teaching_assignments(teacher_id,subject_id,group_id,period_id,active)"
                    + " VALUES(?,?,?,?,?)",
                value.teacherId(),
                value.subjectId(),
                value.groupId(),
                value.periodId(),
                value.active());
          else
            changed(
                execute(
                    c,
                    "UPDATE teaching_assignments SET"
                        + " teacher_id=?,subject_id=?,group_id=?,period_id=?,active=? WHERE id=?",
                    value.teacherId(),
                    value.subjectId(),
                    value.groupId(),
                    value.periodId(),
                    value.active(),
                    value.id()));
          long id = value.id() == 0 ? lastId(c) : value.id();
          audit(c, actor, "assignment", id, before, value.toString(), value.subjectId());
          return new Assignment(
              id,
              value.teacherId(),
              value.subjectId(),
              value.groupId(),
              value.periodId(),
              value.active());
        });
  }

  private void changed(int count) {
    if (count != 1) throw new ValidationException("Запись не найдена.");
  }

  private void exists(Connection c, String table, long id, boolean active) throws SQLException {
    // Имена таблиц задаёт только код сервиса.
    if (list(
            c,
            "SELECT id FROM " + table + " WHERE id=?" + (active ? " AND active=1" : ""),
            r -> r.getLong(1),
            id)
        .isEmpty())
      throw new ValidationException("Связанная запись не найдена или находится в архиве.");
  }

  private void unique(Connection c, String table, String column, String value, long id)
      throws SQLException {
    if (!list(
            c,
            "SELECT id FROM " + table + " WHERE " + column + "=? COLLATE NOCASE AND id<>?",
            r -> r.getLong(1),
            value,
            id)
        .isEmpty())
      throw new ValidationException("Такой код или название уже существует, включая архив.");
  }

  private String old(Connection c, String table, long id) throws SQLException {
    var rows =
        list(
            c,
            "SELECT * FROM " + table + " WHERE id=?",
            r -> {
              var text = new StringBuilder();
              for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                text.append(r.getMetaData().getColumnName(i))
                    .append('=')
                    .append(r.getString(i))
                    .append(';');
              return text.toString();
            },
            id);
    if (id > 0 && rows.isEmpty()) throw new ValidationException("Запись не найдена.");
    return rows.isEmpty() ? "Новая запись" : rows.getFirst();
  }

  private void audit(
      Connection c, User actor, String type, long id, String before, String after, Long subjectId)
      throws SQLException {
    AuditLog.write(
        c,
        actor.login(),
        "SAVE_" + type.toUpperCase(Locale.ROOT),
        type,
        id,
        subjectId,
        "SUCCESS",
        before + " -> " + after);
  }
}
