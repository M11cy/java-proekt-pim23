package ru.exam.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Чистый расчёт rank-v1, без округления промежуточных значений. */
public final class QuestionRanker {
  public record Statistics(
      long uses, long attempts, double ratioSum, LocalDate reviewedOn, boolean synthetic) {
    public Statistics {
      if (uses < 0
          || attempts < 0
          || !Double.isFinite(ratioSum)
          || ratioSum < 0
          || ratioSum > attempts
          || reviewedOn == null) throw new ValidationException("Некорректная статистика вопроса.");
    }
  }

  public record Result(
      Question question,
      double score,
      List<Double> factors,
      List<Double> contributions,
      long uses,
      long attempts,
      Double observedDifficulty,
      String warning,
      boolean synthetic) {}

  public Result calculate(Question q, RankingProfile profile, Statistics stats, LocalDate date) {
    if (q.archived())
      throw new ValidationException("Архивные вопросы не участвуют в ранжировании.");
    if (date == null || stats.reviewedOn().isAfter(date))
      throw new ValidationException("Дата пересмотра находится в будущем относительно расчёта.");
    double observed =
        stats.attempts() < profile.minimumSamples()
            ? Double.NaN
            : 1 + 4 * (1 - stats.ratioSum() / stats.attempts());
    double discrepancy =
        Double.isNaN(observed) ? 0.5 : Math.min(1, Math.abs(observed - q.difficulty()) / 4);
    List<Double> factors =
        List.of(
            (q.importance() - 1) / 4.0,
            1.0 / (1 + stats.uses()),
            1 - Math.abs(q.difficulty() - profile.target()) / 4.0,
            Math.max(
                0,
                1 - ChronoUnit.DAYS.between(stats.reviewedOn(), date) / (double) profile.horizon()),
            discrepancy);
    double sum = profile.weights().stream().mapToDouble(Double::doubleValue).sum(), score = 0;
    var contributions = new ArrayList<Double>();
    for (int i = 0; i < 5; i++) {
      double value = 100 * profile.weights().get(i) / sum * factors.get(i);
      contributions.add(value);
      score += value;
    }
    String warning =
        Double.isNaN(observed)
            ? "Недостаточно наблюдений: " + stats.attempts() + " из " + profile.minimumSamples()
            : Math.abs(observed - q.difficulty()) >= profile.warningThreshold()
                ? "Проверьте заявленную сложность; ответов: " + stats.attempts()
                : "";
    return new Result(
        q,
        score,
        factors,
        List.copyOf(contributions),
        stats.uses(),
        stats.attempts(),
        Double.isNaN(observed) ? null : observed,
        warning,
        stats.synthetic());
  }
}
