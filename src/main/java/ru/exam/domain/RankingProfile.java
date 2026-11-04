package ru.exam.domain;

import java.util.List;

/**
 * Порядок весов: значимость, редкость использования, целевая сложность, актуальность, расхождение.
 */
public record RankingProfile(
    long id,
    long subjectId,
    String name,
    int version,
    int target,
    List<Double> weights,
    int horizon,
    int minimumSamples,
    double warningThreshold) {
  public static final String FORMULA = "rank-v1";

  public RankingProfile {
    name = Organization.text(name, "Название профиля", 100);
    if (id < 0
        || subjectId <= 0
        || version < 0
        || target < 1
        || target > 5
        || horizon < 1
        || horizon > 36500
        || minimumSamples < 1
        || minimumSamples > 1000000)
      throw new ValidationException(
          "Проверьте цель 1..5, горизонт 1..36500 и минимальную выборку 1..1000000.");
    if (weights == null
        || weights.size() != 5
        || weights.stream().anyMatch(w -> w == null || !Double.isFinite(w) || w < 0))
      throw new ValidationException("Нужны пять конечных неотрицательных весов.");
    double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
    if (!Double.isFinite(sum) || sum <= 0)
      throw new ValidationException("Сумма весов должна быть положительной.");
    if (!Double.isFinite(warningThreshold) || warningThreshold < 0 || warningThreshold > 4)
      throw new ValidationException("Порог расхождения: 0..4.");
    weights = List.copyOf(weights);
  }

  @Override
  public String toString() {
    return name + " / v" + version;
  }
}
