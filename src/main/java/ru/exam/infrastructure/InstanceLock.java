package ru.exam.infrastructure;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Файл блокировки сохраняется; владение определяется системной блокировкой, а не его наличием. */
public final class InstanceLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private InstanceLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  public static InstanceLock acquire(Path directory) throws IOException {
    Files.createDirectories(directory);
    FileChannel channel =
        FileChannel.open(
            directory.resolve("application.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) throw new IOException("Приложение для этого каталога данных уже запущено.");
      return new InstanceLock(channel, lock);
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new IOException("Приложение для этого каталога данных уже запущено.", e);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (lock.isValid()) lock.release();
    } finally {
      channel.close();
    }
  }
}
