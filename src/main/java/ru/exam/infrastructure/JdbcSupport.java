package ru.exam.infrastructure;

import java.sql.*;
import java.util.*;

public final class JdbcSupport {
  private JdbcSupport() {}

  @FunctionalInterface
  public interface Mapper<T> {
    T map(ResultSet row) throws SQLException;
  }

  public static <T> List<T> list(Connection c, String sql, Mapper<T> mapper, Object... args)
      throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      bind(s, args);
      var result = new ArrayList<T>();
      try (var r = s.executeQuery()) {
        while (r.next()) result.add(mapper.map(r));
      }
      return List.copyOf(result);
    }
  }

  public static int execute(Connection c, String sql, Object... args) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    }
  }

  public static long lastId(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT last_insert_rowid()")) {
      r.next();
      return r.getLong(1);
    }
  }

  private static void bind(PreparedStatement s, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
  }
}
