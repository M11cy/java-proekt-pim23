package ru.exam.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;

/** Соединение на операцию; транзакция принадлежит всему пользовательскому сценарию. */
public final class Database {
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private final Path path;

  public Database(Path path) {
    this.path = path.toAbsolutePath().normalize();
  }

  public Path path() {
    return path;
  }

  private Connection connect() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
    try (var s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
      try (var rs = s.executeQuery("PRAGMA foreign_keys")) {
        if (!rs.next() || rs.getInt(1) != 1)
          throw new SQLException("Внешние ключи SQLite отключены.");
      }
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  public <T> T read(Work<T> work) {
    try (var c = connect()) {
      return work.run(c);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized <T> T transaction(Work<T> work) {
    try (var c = connect()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private StorageException failure(SQLException e) {
    return new StorageException(
        "Не удалось выполнить операцию с базой. Проверьте доступ к каталогу и свободное место. Код"
            + " SQLite: "
            + e.getErrorCode(),
        e);
  }

  public void initialize() {
    try {
      Files.createDirectories(path.getParent());
      String sql;
      try (var in = Database.class.getResourceAsStream("/db/V1__catalog.sql")) {
        if (in == null) throw new IllegalStateException("Файл миграции отсутствует.");
        sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      String checksum =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(sql.getBytes(StandardCharsets.UTF_8)));
      transaction(
          c -> {
            boolean exists;
            try (var s = c.createStatement();
                var r =
                    s.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND"
                            + " name='schema_version'")) {
              exists = r.next() && r.getInt(1) > 0;
            }
            if (exists) {
              try (var s = c.createStatement();
                  var r =
                      s.executeQuery(
                          "SELECT version,checksum FROM schema_version ORDER BY version DESC LIMIT"
                              + " 1")) {
                if (!r.next() || r.getInt(1) != 1 || !checksum.equals(r.getString(2)))
                  throw new IllegalStateException(
                      "Версия или контрольная сумма схемы не поддерживается. База не изменена.");
              }
            } else {
              try (var s = c.createStatement()) {
                for (String statement : sql.split(";"))
                  if (!statement.isBlank()) s.execute(statement);
              }
              try (var s = c.prepareStatement("INSERT INTO schema_version VALUES(1,?,?)")) {
                s.setString(1, checksum);
                s.setString(2, Instant.now().toString());
                s.executeUpdate();
              }
            }
            return null;
          });
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
      throw new StorageException("Не удалось инициализировать базу.", e);
    }
  }
}
