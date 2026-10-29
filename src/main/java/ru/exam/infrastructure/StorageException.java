package ru.exam.infrastructure;

public final class StorageException extends RuntimeException {
  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
