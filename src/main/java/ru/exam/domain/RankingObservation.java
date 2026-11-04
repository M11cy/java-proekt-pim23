package ru.exam.domain;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** Сводка завершённых ответов одной ревизии; eventKey предотвращает повторную загрузку. */
public record RankingObservation(
    String eventKey,
    long questionId,
    int revision,
    LocalDate occurredOn,
    int uses,
    int attempts,
    double ratioSum,
    String source,
    boolean synthetic) {
  public RankingObservation {
    eventKey = Organization.text(eventKey, "Ключ наблюдения", 100);
    if (questionId <= 0
        || revision < 1
        || occurredOn == null
        || occurredOn.isAfter(LocalDate.now(ZoneOffset.UTC)))
      throw new ValidationException("Проверьте вопрос, ревизию и дату наблюдения.");
    if (uses < 0
        || attempts < 0
        || uses > 1000000
        || attempts > 1000000
        || (uses == 0 && attempts == 0)
        || !Double.isFinite(ratioSum)
        || ratioSum < 0
        || ratioSum > attempts)
      throw new ValidationException(
          "Использования и ответы: 0..1000000; сумма долей: 0..число ответов. Набор не должен быть"
              + " пустым.");
    if (!"MANUAL".equals(source) && !"EXAM".equals(source))
      throw new ValidationException("Неизвестный источник статистики.");
  }
}
