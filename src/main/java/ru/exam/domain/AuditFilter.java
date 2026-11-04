package ru.exam.domain;

import java.time.LocalDate;

public record AuditFilter(
    LocalDate from, LocalDate until, String action, String objectType, String text) {
  public AuditFilter {
    if (from != null && until != null && from.isAfter(until))
      throw new ValidationException("Начальная дата позже конечной.");
    action = action == null ? "" : action.strip();
    objectType = objectType == null ? "" : objectType.strip();
    text = text == null ? "" : text.strip();
    if (text.length() > 200)
      throw new ValidationException("Поиск в журнале: не более 200 символов.");
  }

  public static AuditFilter all() {
    return new AuditFilter(null, null, "", "", "");
  }
}
