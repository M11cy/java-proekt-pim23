package ru.exam.domain;

import java.util.Locale;

public record Subject(long id, String code, String name) {
  public Subject {
    if (id < 0) throw new ValidationException("Некорректный идентификатор.");
    if (code == null || code.isBlank() || code.strip().length() > 40)
      throw new ValidationException("Код: от 1 до 40 символов.");
    if (name == null || name.isBlank() || name.strip().length() > 200)
      throw new ValidationException("Название: от 1 до 200 символов.");
    code = code.strip().toUpperCase(Locale.ROOT);
    name = name.strip();
  }

  @Override
  public String toString() {
    return name + " [" + code + "]";
  }
}
