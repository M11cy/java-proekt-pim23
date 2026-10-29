package ru.exam.domain;

public final class ValidationException extends RuntimeException {
  public ValidationException(String message) {
    super(message);
  }
}
