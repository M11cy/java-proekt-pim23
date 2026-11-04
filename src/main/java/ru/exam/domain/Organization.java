package ru.exam.domain;

import java.time.LocalDate;
import java.util.Locale;

public final class Organization {
  private Organization() {}

  public static String text(String value, String field, int maximum) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > maximum)
      throw new ValidationException(field + ": от 1 до " + maximum + " символов.");
    return value.strip();
  }

  public static String code(String value) {
    return text(value, "Код", 40).toUpperCase(Locale.ROOT);
  }

  public record Group(long id, String code, String name, boolean active) {
    public Group {
      if (id < 0) throw new ValidationException("Некорректный ID группы.");
      code = Organization.code(code);
      name = text(name, "Название группы", 200);
    }

    @Override
    public String toString() {
      return code + ": " + name + (active ? "" : " (архив)");
    }
  }

  public record Student(long id, long groupId, String number, String name, boolean active) {
    public Student {
      if (id < 0 || groupId <= 0) throw new ValidationException("Выберите группу студента.");
      number = Organization.code(number);
      name = text(name, "ФИО", 200);
    }
  }

  public record Period(long id, String name, LocalDate startsOn, LocalDate endsOn, boolean active) {
    public Period {
      if (id < 0) throw new ValidationException("Некорректный ID периода.");
      name = text(name, "Название периода", 200);
      if (startsOn == null || endsOn == null || startsOn.isAfter(endsOn))
        throw new ValidationException("Начало периода должно быть не позже окончания.");
    }

    @Override
    public String toString() {
      return name + (active ? "" : " (архив)");
    }
  }

  public record Assignment(
      long id, long teacherId, long subjectId, long groupId, long periodId, boolean active) {
    public Assignment {
      if (id < 0 || teacherId <= 0 || subjectId <= 0 || groupId <= 0 || periodId <= 0)
        throw new ValidationException("Выберите преподавателя, дисциплину, группу и период.");
    }
  }
}
