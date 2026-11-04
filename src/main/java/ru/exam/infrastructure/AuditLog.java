package ru.exam.infrastructure;

import java.sql.*;
import java.time.Instant;

public final class AuditLog {
  private AuditLog() {}

  public static void write(
      Connection c,
      String actor,
      String action,
      String type,
      long id,
      Long subjectId,
      String result,
      String details)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " audit_log(occurred_at,actor,action,object_type,object_id,details,subject_id,result)"
                + " VALUES(?,?,?,?,?,?,?,?)")) {
      s.setString(1, Instant.now().toString());
      s.setString(2, actor);
      s.setString(3, action);
      s.setString(4, type);
      s.setLong(5, id);
      s.setString(6, details);
      if (subjectId == null) s.setNull(7, Types.INTEGER);
      else s.setLong(7, subjectId);
      s.setString(8, result);
      s.executeUpdate();
    }
  }
}
