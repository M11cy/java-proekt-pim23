package ru.exam.domain;

import java.math.BigDecimal;
import java.util.*;

public record TicketSet(
    long id,
    long subjectId,
    String subject,
    String name,
    Config config,
    List<Item> bank,
    List<Long> initial,
    List<Long> selected,
    Header header,
    int version) {
  public TicketSet {
    bank = List.copyOf(bank);
    initial = List.copyOf(initial);
    selected = List.copyOf(selected);
  }

  public boolean approved() {
    return header != null;
  }

  public Map<Long, Item> items() {
    var items = new HashMap<Long, Item>();
    bank.forEach(item -> items.put(item.id(), item));
    return items;
  }

  public record Item(
      long id,
      int revision,
      long topicId,
      String topic,
      QuestionType type,
      int difficulty,
      BigDecimal points,
      double rank,
      String text) {
    @Override
    public String toString() {
      return topic + " / " + type + " / " + text.replace('\n', ' ');
    }
  }

  public record Slot(Long topic, QuestionType type, int min, int max) {
    public Slot {
      if (min < 1 || max > 5 || min > max)
        throw new ValidationException("Диапазон сложности позиции должен быть от 1 до 5.");
    }

    public boolean accepts(Item item) {
      return (topic == null || item.topicId() == topic)
          && (type == null || item.type() == type)
          && item.difficulty() >= min
          && item.difficulty() <= max;
    }
  }

  public record Config(
      int count,
      List<Slot> slots,
      int repeat,
      double target,
      Double deviation,
      long seed,
      int budget,
      Set<Long> required,
      Set<Long> excluded,
      Map<Long, Integer> topicQuotas,
      boolean topicQuotasPerSet,
      Map<QuestionType, Integer> typeQuotas,
      List<Double> weights) {
    public Config {
      slots = List.copyOf(slots);
      required = Set.copyOf(required);
      excluded = Set.copyOf(excluded);
      topicQuotas = Map.copyOf(topicQuotas);
      typeQuotas = Map.copyOf(typeQuotas);
      weights = List.copyOf(weights);
      if (count < 1
          || count > 100
          || slots.isEmpty()
          || slots.size() > 10
          || count * slots.size() > 500
          || repeat < 1
          || repeat > count
          || !Double.isFinite(target)
          || target < 1
          || target > 5
          || (deviation != null && (!Double.isFinite(deviation) || deviation < 0 || deviation > 4))
          || budget < 100
          || budget > 250_000)
        throw new ValidationException(
            "Проверьте количество, лимит повторов, сложность и лимит поиска. Максимум 500 мест.");
      if (!Collections.disjoint(required, excluded) || required.size() > count * slots.size())
        throw new ValidationException("Проверьте списки обязательных и исключённых вопросов.");
      validateQuota(topicQuotas, topicQuotasPerSet ? count * slots.size() : slots.size());
      validateQuota(typeQuotas, slots.size());
      double total = weights.stream().mapToDouble(Double::doubleValue).sum();
      if (weights.size() != 5
          || !Double.isFinite(total)
          || total <= 0
          || weights.stream().anyMatch(w -> !Double.isFinite(w) || w < 0))
        throw new ValidationException("Задайте пять неотрицательных весов с положительной суммой.");
    }

    private static void validateQuota(Map<?, Integer> quotas, int expected) {
      if (!quotas.isEmpty()
          && (quotas.values().stream().anyMatch(n -> n < 0 || n > expected)
              || quotas.values().stream().mapToInt(Integer::intValue).sum() != expected))
        throw new ValidationException("Точные квоты должны в сумме давать " + expected + ".");
    }

    public int size() {
      return slots.size();
    }
  }

  public record Header(String organization, String period, String signature, String approval) {
    public Header {
      organization = Organization.text(organization, "Организация", 160);
      period = Organization.text(period, "Учебный период", 100);
      signature = Organization.text(signature, "Подпись", 120);
      approval = approval == null ? "" : approval.strip();
      if (approval.length() > 120)
        throw new ValidationException("Строка утверждения: до 120 символов.");
    }
  }
}
