package ru.exam.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.sqlite.SQLiteConfig;

/** Все соединения закрываются до замены файла; операции сериализованы на экземпляре базы. */
public final class Database {
  private static final List<String> MIGRATIONS =
      List.of("V1__catalog.sql", "V2__organization_ranking.sql", "V3__question_rankings.sql");

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
      try (var r = s.executeQuery("PRAGMA foreign_keys")) {
        if (!r.next() || r.getInt(1) != 1) throw new SQLException("Внешние ключи отключены.");
      }
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  public synchronized <T> T read(Work<T> work) {
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
        "Операция с базой не выполнена. Проверьте доступ и свободное место. Код SQLite: "
            + e.getErrorCode(),
        e);
  }

  private static String script(int index) {
    try (var in = Database.class.getResourceAsStream("/db/" + MIGRATIONS.get(index))) {
      if (in == null) throw new IllegalStateException("Файл миграции отсутствует.");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new StorageException("Не удалось прочитать миграцию.", e);
    }
  }

  private static String checksum(String sql) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int version(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
      boolean any = false, versions = false;
      while (r.next()) {
        any = true;
        versions |= r.getString(1).equals("schema_version");
      }
      if (!versions) {
        if (any) throw new IllegalStateException("Неизвестная структура базы. Файл не изменён.");
        return 0;
      }
    }
    int count = 0;
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT version,checksum FROM schema_version ORDER BY version")) {
      while (r.next()) {
        int v = r.getInt(1);
        if (v != count + 1
            || v > MIGRATIONS.size()
            || !checksum(script(v - 1)).equals(r.getString(2)))
          throw new IllegalStateException("Версия или контрольная сумма схемы не поддерживается.");
        count++;
      }
    }
    if (count == 0) throw new IllegalStateException("История миграций пуста.");
    return count;
  }

  public synchronized void initialize() {
    initialize(true);
  }

  public static void prepareRestore(Path staged) {
    validateBackup(staged);
    new Database(staged).initialize(false);
  }

  private void initialize(boolean keepMigrationBackup) {
    try {
      Files.createDirectories(path.getParent());
      int previous = read(Database::version);
      if (keepMigrationBackup && previous > 0 && previous < MIGRATIONS.size()) {
        Path dir = path.getParent().resolve("backups");
        Files.createDirectories(dir);
        snapshot(dir.resolve("before-migration-" + UUID.randomUUID() + ".db"));
      }
      transaction(
          c -> {
            for (int i = previous; i < MIGRATIONS.size(); i++) {
              try (var s = c.createStatement()) {
                for (String sql : script(i).split(";")) if (!sql.isBlank()) s.execute(sql);
              }
              try (var s = c.prepareStatement("INSERT INTO schema_version VALUES(?,?,?)")) {
                s.setInt(1, i + 1);
                s.setString(2, checksum(script(i)));
                s.setString(3, Instant.now().toString());
                s.executeUpdate();
              }
            }
            checkIntegrity(c);
            return null;
          });
      restrict(path);
    } catch (IOException e) {
      throw new StorageException("Не удалось инициализировать базу.", e);
    }
  }

  public synchronized void snapshot(Path destination) {
    Path target = destination.toAbsolutePath().normalize();
    if (target.equals(path) || Files.exists(target))
      throw new IllegalArgumentException("Выберите новое имя файла копии.");
    Path temporary = null;
    try {
      Files.createDirectories(target.getParent());
      temporary = Files.createTempFile(target.getParent(), ".snapshot-", ".db");
      Files.delete(temporary);
      Path output = temporary;
      read(
          c -> {
            try (var statement = c.prepareStatement("VACUUM INTO ?")) {
              statement.setString(1, output.toString());
              statement.execute();
            }
            return null;
          });
      restrict(temporary);
      Files.move(temporary, target);
    } catch (IOException | RuntimeException e) {
      throw new StorageException("Не удалось создать резервную копию.", e);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          temporary.toFile().deleteOnExit();
        }
    }
  }

  public static void validateBackup(Path source) {
    if (!Files.isRegularFile(source))
      throw new IllegalArgumentException("Выберите файл резервной копии.");
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var c =
        DriverManager.getConnection(
            "jdbc:sqlite:" + source.toAbsolutePath(), config.toProperties())) {
      int backupVersion = version(c);
      if (backupVersion < 2)
        throw new IllegalArgumentException("Копия имеет несовместимую версию схемы.");
      checkIntegrity(c);
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT count(*) FROM users WHERE role='ADMIN' AND active=1")) {
        if (!r.next() || r.getInt(1) == 0)
          throw new IllegalArgumentException("В копии нет действующего администратора.");
      }
      Map<String, String> columns =
          Map.ofEntries(
              Map.entry(
                  "users",
                  "id,login,name,role,active,password_hash,password_version,failed_attempts,locked_until"),
              Map.entry("subjects", "id,code,name"),
              Map.entry("topics", "id,subject_id,code,name"),
              Map.entry("questions", "id,topic_id,current_revision,archived,reviewed_at"),
              Map.entry(
                  "question_revisions",
                  "question_id,revision_no,topic_id,text,normalized_text,type,difficulty,importance,max_points_scaled,guidance,archived,saved_at"),
              Map.entry(
                  "audit_log",
                  "id,occurred_at,actor,action,object_type,object_id,details,subject_id,result"),
              Map.entry("study_groups", "id,code,name,active"),
              Map.entry("students", "id,group_id,number,name,active"),
              Map.entry("study_periods", "id,name,starts_on,ends_on,active"),
              Map.entry(
                  "teaching_assignments", "id,teacher_id,subject_id,group_id,period_id,active"),
              Map.entry(
                  "ranking_profiles",
                  "id,subject_id,name,version,formula,target,weight_i,weight_f,weight_d,weight_a,weight_e,horizon,minimum_samples,warning_threshold,saved_at"),
              Map.entry("question_rankings", "question_id,revision,profile_id,calculated_on,score,difficulty"),
              Map.entry("ranking_word_uses", "document_key,question_id"),
              Map.entry("ranking_exam_discrepancies", "result_key,question_id,revision,discrepancy"),
              Map.entry(
                  "ranking_observations",
                  "id,event_key,question_id,revision,occurred_on,uses,attempts,ratio_sum,source,synthetic"));
      for (var table : columns.entrySet()) {
        if (backupVersion == 2 && List.of("question_rankings", "ranking_word_uses",
            "ranking_exam_discrepancies").contains(table.getKey())) continue;
        try (var s = c.createStatement();
            var r =
                s.executeQuery(
                    "SELECT " + table.getValue() + " FROM " + table.getKey() + " LIMIT 0")) {
          r.getMetaData();
        }
      }
    } catch (SQLException e) {
      throw new StorageException("Файл повреждён или не является резервной копией приложения.", e);
    }
  }

  public static void checkIntegrity(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("PRAGMA integrity_check")) {
      if (!r.next() || !"ok".equals(r.getString(1)))
        throw new IllegalStateException("Проверка целостности базы не пройдена.");
    }
    try (var s = c.createStatement();
        var r = s.executeQuery("PRAGMA foreign_key_check")) {
      if (r.next()) throw new IllegalStateException("Нарушены связи таблиц базы.");
    }
  }

  public static void restrict(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
    }
  }
}
