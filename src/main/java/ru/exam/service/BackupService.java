package ru.exam.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import ru.exam.domain.ValidationException;
import ru.exam.infrastructure.*;

public final class BackupService {
  private final Database database;
  private final AccessControl access;
  private final Session session;

  public BackupService(Database database, AccessControl access, Session session) {
    this.database = database;
    this.access = access;
    this.session = session;
  }

  public Path create(Path destination) {
    synchronized (database) {
      var actor = access.admin();
      Path target = destination.toAbsolutePath().normalize();
      database.snapshot(target);
      Database.validateBackup(target);
      database.transaction(
          c -> {
            AuditLog.write(
                c,
                actor.login(),
                "CREATE_BACKUP",
                "backup",
                0,
                null,
                "SUCCESS",
                "Создана и проверена копия: " + target.getFileName());
            return null;
          });
      return target;
    }
  }

  public void verify(Path source) {
    access.admin();
    Database.validateBackup(source);
  }

  public Path restore(Path source) {
    synchronized (database) {
      var actor = access.admin();
      Path current = database.path();
      Path staged = null;
      Path safety = null;
      boolean replaced = false;
      try {
        if (!Files.isRegularFile(source) || Files.isSameFile(source, current))
          throw new ValidationException("Выберите отдельный файл резервной копии.");
        staged = Files.createTempFile(current.getParent(), ".restore-", ".db");
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
        Database.prepareRestore(staged);
        if (Files.exists(Path.of(current + "-wal")) || Files.exists(Path.of(current + "-journal")))
          throw new ValidationException(
              "У базы есть незавершённый журнал. Перезапустите приложение и повторите"
                  + " восстановление.");
        Path directory = current.getParent().resolve("backups");
        Files.createDirectories(directory);
        safety = directory.resolve("before-restore-" + UUID.randomUUID() + ".db");
        database.snapshot(safety);
        Database.validateBackup(safety);
        Files.move(
            staged, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        replaced = true;
        Path copy = safety;
        database.transaction(
            c -> {
              AuditLog.write(
                  c,
                  actor.login(),
                  "RESTORE_BACKUP",
                  "backup",
                  0,
                  null,
                  "SUCCESS",
                  "Восстановлено из "
                      + source.getFileName()
                      + "; предыдущее состояние: "
                      + copy.getFileName());
              return null;
            });
        session.clear();
        return safety;
      } catch (IOException | RuntimeException e) {
        if (replaced && safety != null) {
          try {
            Files.copy(safety, staged, StandardCopyOption.REPLACE_EXISTING);
            Files.move(
                staged,
                current,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException rollback) {
            e.addSuppressed(rollback);
          }
          session.clear();
        }
        if (e instanceof ValidationException validation) throw validation;
        throw new StorageException(
            "Восстановление не выполнено. Резервная копия предыдущего состояния: "
                + (safety == null ? "замена не начиналась" : safety),
            e);
      } finally {
        if (staged != null)
          try {
            Files.deleteIfExists(staged);
          } catch (IOException e) {
            staged.toFile().deleteOnExit();
          }
      }
    }
  }
}
