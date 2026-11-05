package ru.exam.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class QuestionRanker {
  public static final String FORMULA = "rank-v2";

  public record Result(
      Question question,
      double score,
      int difficulty,
      List<Double> contributions,
      long uses,
      long answers,
      Double discrepancy,
      boolean old) {}

  public Result calculate(
      Question question, RankingProfile profile, long uses, long answers,
      Double discrepancy, LocalDate editedOn, LocalDate date) {
    long age = Math.max(0, ChronoUnit.DAYS.between(editedOn, date));
    var factors = List.of(
        (question.importance() - 1) / 4.0,
        1.0 / (1.0 + uses),
        (question.difficulty() - 1) / 4.0,
        Math.max(0, 1 - age / (double) profile.horizon()),
        discrepancy == null ? 0.5 : discrepancy / 4.0);

    double sum = profile.weights().stream().mapToDouble(Double::doubleValue).sum();
    double score = 0;
    var contributions = new ArrayList<Double>();
    for (int i = 0; i < factors.size(); i++) {
      double value = (profile.weights().get(i) / sum) * 100 * factors.get(i);
      contributions.add(value);
      score += value;
    }
    score = Math.min(100, score);
    int difficulty = 1 + (int) Math.round(score / 25);
    return new Result(question, score, difficulty, List.copyOf(contributions),
        uses, answers, discrepancy, age >= profile.horizon());
  }
}
