package ru.exam.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;

/** Неизменяемое состояние одной версии вопроса. Сохранение версий выполняет репозиторий. */
public final class Question {
  private final long id;
  private final long topicId;
  private final String text;
  private final QuestionType type;
  private final int difficulty;
  private final int importance;
  private final BigDecimal maxPoints;
  private final String guidance;
  private final boolean archived;
  private final int revision;

  public Question(
      long id,
      long topicId,
      String text,
      QuestionType type,
      int difficulty,
      int importance,
      BigDecimal maxPoints,
      String guidance,
      boolean archived,
      int revision) {
    if (id < 0 || topicId <= 0 || revision < 0)
      throw new ValidationException("Некорректный идентификатор вопроса или темы.");
    if (text == null || normalize(text).isEmpty() || text.strip().length() > 10_000)
      throw new ValidationException("Текст вопроса: от 1 до 10 000 символов.");
    if (type == null) throw new ValidationException("Выберите тип вопроса.");
    if (difficulty < 1 || difficulty > 5)
      throw new ValidationException("Сложность: целое число от 1 до 5.");
    if (importance < 1 || importance > 5)
      throw new ValidationException("Значимость: целое число от 1 до 5.");
    if (maxPoints == null
        || maxPoints.signum() <= 0
        || maxPoints.compareTo(new BigDecimal("999999.99")) > 0)
      throw new ValidationException("Максимальный балл: от 0,01 до 999999,99.");
    try {
      this.maxPoints = maxPoints.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new ValidationException("Баллы должны иметь не более двух десятичных знаков.");
    }
    if (guidance != null && guidance.length() > 20_000)
      throw new ValidationException("Критерий ответа: не более 20 000 символов.");
    this.id = id;
    this.topicId = topicId;
    this.text = text.strip();
    this.type = type;
    this.difficulty = difficulty;
    this.importance = importance;
    this.guidance = guidance == null ? "" : guidance.strip();
    this.archived = archived;
    this.revision = revision;
  }

  public Question archive() {
    return withArchived(true);
  }

  public Question restore() {
    return withArchived(false);
  }

  private Question withArchived(boolean value) {
    return new Question(
        id, topicId, text, type, difficulty, importance, maxPoints, guidance, value, revision);
  }

  public Question identified(long id, int revision) {
    return new Question(
        id, topicId, text, type, difficulty, importance, maxPoints, guidance, archived, revision);
  }

  public static String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .strip()
        .replaceAll("(?U)\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public String normalizedText() {
    return normalize(text);
  }

  public long id() {
    return id;
  }

  public long topicId() {
    return topicId;
  }

  public String text() {
    return text;
  }

  public QuestionType type() {
    return type;
  }

  public int difficulty() {
    return difficulty;
  }

  public int importance() {
    return importance;
  }

  public BigDecimal maxPoints() {
    return maxPoints;
  }

  public String guidance() {
    return guidance;
  }

  public boolean archived() {
    return archived;
  }

  public int revision() {
    return revision;
  }
}
