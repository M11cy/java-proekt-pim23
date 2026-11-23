package ru.exam.domain;

import static ru.exam.domain.TicketSet.*;

import java.util.*;

public final class TicketOptimizer {
  public static final String VERSION = "tickets-v1";

  public record Metrics(
      double average,
      double minimum,
      double maximum,
      double spread,
      double deviation,
      int repeats,
      double bankCoverage,
      double uniqueShare,
      int coveredTopics,
      int availableTopics,
      double averageRank,
      double quality) {}

  public record Selection(List<Long> initial, List<Long> best) {}

  private final Config config;
  private final Map<Long, Item> bank = new LinkedHashMap<>();
  private final List<List<Item>> options;
  private final Set<Long> allowedTopics = new TreeSet<>();
  private final double minTotal;
  private final double maxTotal;

  public TicketOptimizer(Config config, List<Item> candidates) {
    this.config = config;
    candidates.stream()
        .sorted(Comparator.comparingLong(Item::id))
        .filter(item -> !config.excluded().contains(item.id()))
        .forEach(item -> bank.put(item.id(), item));
    options =
        config.slots().stream()
            .map(
                slot ->
                    bank.values().stream()
                        .filter(slot::accepts)
                        .filter(
                            item ->
                                permitted(config.topicQuotas(), item.topicId())
                                    && permitted(config.typeQuotas(), item.type()))
                        .toList())
            .toList();
    options.forEach(items -> items.forEach(item -> allowedTopics.add(item.topicId())));
    minTotal =
        config.deviation() == null ? 0 : (config.target() - config.deviation()) * config.size();
    maxTotal =
        config.deviation() == null
            ? Double.POSITIVE_INFINITY
            : (config.target() + config.deviation()) * config.size();
  }

  private static <T> boolean permitted(Map<T, Integer> quota, T key) {
    return quota.isEmpty() || quota.getOrDefault(key, 0) > 0;
  }

  private void preflight() {
    int places = config.count() * config.size();
    if (bank.size() < config.size() || (long) bank.size() * config.repeat() < places)
      throw new ValidationException(
          "Недостаточно вопросов. Нужно мест: "
              + places
              + ", доступно включений: "
              + (long) bank.size() * config.repeat()
              + ".");
    int min = 0, max = 0;
    var pool = new HashSet<Long>();
    for (int i = 0; i < options.size(); i++) {
      var choices = options.get(i);
      if ((long) choices.size() * config.repeat() < config.count())
        throw new ValidationException(
            "Недостаточно вопросов для позиции "
                + (i + 1)
                + ". Подходит вопросов: "
                + choices.size()
                + ".");
      min += choices.stream().mapToInt(Item::difficulty).min().orElseThrow();
      max += choices.stream().mapToInt(Item::difficulty).max().orElseThrow();
      choices.forEach(item -> pool.add(item.id()));
    }
    if (pool.size() < config.size() || (long) pool.size() * config.repeat() < places)
      throw new ValidationException("Совместно для заданных позиций недостаточно разных вопросов.");
    if (!pool.containsAll(config.required()))
      throw new ValidationException(
          "Обязательный вопрос не соответствует ни одной разрешённой позиции.");
    if (min > maxTotal + 1e-9 || max < minTotal - 1e-9)
      throw new ValidationException("Диапазон сложности несовместим с выбранными позициями.");
    for (var quota : config.topicQuotas().entrySet()) {
      long available =
          pool.stream().map(bank::get).filter(q -> q.topicId() == quota.getKey()).count();
      int perTicket = config.topicQuotasPerSet() ? config.size() : quota.getValue();
      int total = config.topicQuotasPerSet() ? quota.getValue() : quota.getValue() * config.count();
      if (available * config.repeat() < total
          || (!config.topicQuotasPerSet() && available < perTicket))
        throw new ValidationException("Недостаточно вопросов для одной из квот по темам.");
    }
    for (var quota : config.typeQuotas().entrySet()) {
      long available = pool.stream().map(bank::get).filter(q -> q.type() == quota.getKey()).count();
      if (available < quota.getValue()
          || available * config.repeat() < quota.getValue() * config.count())
        throw new ValidationException("Недостаточно вопросов для квоты «" + quota.getKey() + "».");
    }
  }

  public Selection generate() {
    preflight();
    var random = new Random(config.seed());
    List<Long> initial = null, best = null;
    double bestQuality = -1;
    int budget = config.budget();
    for (int n = 0; n < 5 && budget > 0; n++) {
      int limit = Math.min(budget, Math.max(config.count() * config.size(), config.budget() / 5));
      var search = new Search(random, limit);
      boolean found = search.fill(0);
      budget -= limit;
      if (!found) continue;
      var candidate = Arrays.stream(search.selection).boxed().toList();
      validate(candidate);
      if (initial == null) initial = candidate;
      double quality = metrics(candidate).quality();
      if (quality > bestQuality) {
        best = candidate;
        bestQuality = quality;
      }
    }
    if (best == null)
      throw new ValidationException(
          "Поиск завершился без подходящего комплекта. Попробуйте создать его снова или измените условия.");
    return new Selection(initial, improve(best));
  }

  private final class Search {
    private final long[] selection = new long[config.size() * config.count()];
    private final int[] totals = new int[config.count()];
    private final Map<Long, Integer> uses = new HashMap<>();
    private final List<Map<Long, Integer>> ticketTopics = new ArrayList<>();
    private final Map<Long, Integer> allTopics = new HashMap<>();
    private final List<Map<QuestionType, Integer>> ticketTypes = new ArrayList<>();
    private final Map<Long, Double> priority = new HashMap<>();
    private int remaining;
    private long candidateChecks = Math.max(1_000_000L, (long) bank.size() * selection.length);

    Search(Random random, int budget) {
      remaining = budget + 1;
      for (int i = 0; i < config.count(); i++) {
        ticketTopics.add(new HashMap<>());
        ticketTypes.add(new EnumMap<>(QuestionType.class));
      }
      bank.values()
          .forEach(
              q ->
                  priority.put(
                      q.id(), -Math.log(1.0 - random.nextDouble()) / (0.1 + q.rank() / 100)));
    }

    boolean fill(int index) {
      if (--remaining < 0 || candidateChecks < 0) return false;
      if (index == selection.length) return uses.keySet().containsAll(config.required());
      long missing = config.required().stream().filter(id -> !uses.containsKey(id)).count();
      if (missing > selection.length - index) return false;
      int ticket = index / config.size(), slot = index % config.size();
      var topics = config.topicQuotasPerSet() ? allTopics : ticketTopics.get(ticket);
      var types = ticketTypes.get(ticket);
      int left = config.size() - slot - 1;
      candidateChecks -= options.get(slot).size();
      if (candidateChecks < 0) return false;
      var candidates = new ArrayList<Item>();
      for (var q : options.get(slot)) {
        if (uses.getOrDefault(q.id(), 0) >= config.repeat()
            || (missing == selection.length - index
                && (!config.required().contains(q.id()) || uses.containsKey(q.id())))
            || alreadyUsed(index, slot, q.id())
            || exceeds(config.topicQuotas(), topics, q.topicId())
            || exceeds(config.typeQuotas(), types, q.type())) continue;
        int total = totals[ticket] + q.difficulty();
        if (total + left > maxTotal + 1e-9 || total + 5 * left < minTotal - 1e-9) continue;
        candidates.add(q);
      }
      candidates.sort(
          Comparator.comparingDouble(
              q ->
                  priority.get(q.id())
                      + 2.0 * uses.getOrDefault(q.id(), 0)
                      + Math.abs(
                          (totals[ticket] + q.difficulty()) / (double) (slot + 1) - config.target())
                      - (config.required().contains(q.id()) && !uses.containsKey(q.id())
                          ? 1000
                          : 0)));
      for (var q : candidates) {
        selection[index] = q.id();
        adjust(uses, q.id(), 1);
        adjust(topics, q.topicId(), 1);
        adjust(types, q.type(), 1);
        totals[ticket] += q.difficulty();
        if (fill(index + 1)) return true;
        totals[ticket] -= q.difficulty();
        adjust(uses, q.id(), -1);
        adjust(topics, q.topicId(), -1);
        adjust(types, q.type(), -1);
        if (remaining < 0 || candidateChecks < 0) break;
      }
      return false;
    }

    private boolean alreadyUsed(int index, int slot, long id) {
      for (int i = index - slot; i < index; i++) if (selection[i] == id) return true;
      return false;
    }
  }

  private static <T> void adjust(Map<T, Integer> counts, T key, int change) {
    int count = counts.getOrDefault(key, 0) + change;
    if (count == 0) counts.remove(key);
    else counts.put(key, count);
  }

  private static <T> boolean exceeds(Map<T, Integer> quota, Map<T, Integer> counts, T key) {
    return !quota.isEmpty() && counts.getOrDefault(key, 0) >= quota.getOrDefault(key, 0);
  }

  public void validate(List<Long> selection) {
    String error = violation(selection);
    if (error != null) throw new ValidationException(error);
  }

  private String violation(List<Long> selection) {
    if (selection.size() != config.count() * config.size()) return "Неверное число вопросов.";
    var counts = new HashMap<Long, Integer>();
    var allTopics = new HashMap<Long, Integer>();
    for (int start = 0; start < selection.size(); start += config.size()) {
      var distinct = new HashSet<Long>();
      var topics = new HashMap<Long, Integer>();
      var types = new EnumMap<QuestionType, Integer>(QuestionType.class);
      int total = 0;
      int ticket = start / config.size() + 1;
      for (int slot = 0; slot < config.size(); slot++) {
        long id = selection.get(start + slot);
        var q = bank.get(id);
        if (q == null || !config.slots().get(slot).accepts(q))
          return "Билет "
              + ticket
              + ", позиция "
              + (slot + 1)
              + ": вопрос не соответствует условиям.";
        if (!distinct.add(id)) return "Билет " + ticket + ": вопрос повторяется.";
        if (counts.merge(id, 1, Integer::sum) > config.repeat()) return "Превышен предел повторов.";
        topics.merge(q.topicId(), 1, Integer::sum);
        allTopics.merge(q.topicId(), 1, Integer::sum);
        types.merge(q.type(), 1, Integer::sum);
        total += q.difficulty();
      }
      if (total < minTotal - 1e-9 || total > maxTotal + 1e-9)
        return "Билет " + ticket + ": средняя сложность за пределами допуска.";
      if ((!config.topicQuotasPerSet() && !matches(config.topicQuotas(), topics))
          || !matches(config.typeQuotas(), types))
        return "Билет " + ticket + ": нарушена точная квота.";
    }
    if (config.topicQuotasPerSet() && !matches(config.topicQuotas(), allTopics))
      return "Нарушена квота тем на комплект.";
    if (!counts.keySet().containsAll(config.required())) return "Отсутствует обязательный вопрос.";
    return null;
  }

  private static <T> boolean matches(Map<T, Integer> quota, Map<T, Integer> actual) {
    return quota.isEmpty()
        || (actual.keySet().stream().allMatch(quota::containsKey)
            && quota.entrySet().stream()
                .allMatch(e -> e.getValue().equals(actual.getOrDefault(e.getKey(), 0))));
  }

  public List<Long> improve(List<Long> selection) {
    validate(selection);
    var random = new Random(config.seed() ^ 0x541089L);
    var best = new ArrayList<>(selection);
    double quality = metrics(best).quality();
    for (int step = 0; step < Math.min(3000, config.budget()); step++) {
      int i = random.nextInt(best.size());
      int j = random.nextInt(best.size());
      long old = best.get(i), other = best.get(j);
      var choices = options.get(i % config.size());
      boolean swap = step % 2 == 0 && i != j;
      long replacement = swap ? other : choices.get(random.nextInt(choices.size())).id();
      if (old == replacement) continue;
      best.set(i, replacement);
      if (swap) best.set(j, old);
      if (violation(best) == null) {
        double next = metrics(best).quality();
        if (next > quality + 1e-9) {
          quality = next;
          continue;
        }
      }
      best.set(i, old);
      if (swap) best.set(j, other);
    }
    return List.copyOf(best);
  }

  public Metrics metrics(List<Long> selection) {
    double[] means = new double[config.count()];
    double rank = 0;
    var topics = new TreeMap<Long, Integer>();
    for (int i = 0; i < selection.size(); i++) {
      Item item = bank.get(selection.get(i));
      means[i / config.size()] += item.difficulty() / (double) config.size();
      rank += item.rank();
      topics.merge(item.topicId(), 1, Integer::sum);
    }
    double mean = Arrays.stream(means).average().orElseThrow();
    double spread =
        Math.sqrt(Arrays.stream(means).map(d -> (d - mean) * (d - mean)).average().orElseThrow());
    double deviation =
        Arrays.stream(means).map(d -> Math.abs(d - config.target())).average().orElseThrow();
    int repeats = selection.size() - new HashSet<>(selection).size();
    double topicPenalty = 0;
    if (config.topicQuotas().isEmpty()) {
      for (long topic : allowedTopics)
        topicPenalty +=
            Math.abs(
                    topics.getOrDefault(topic, 0) / (double) selection.size()
                        - 1.0 / allowedTopics.size())
                / 2;
    }
    double averageRank = rank / selection.size();
    double[] penalties = {
      Math.min(1, spread / 2),
      deviation / 4,
      repeats / (double) selection.size(),
      topicPenalty,
      1 - averageRank / 100
    };
    double sum = config.weights().stream().mapToDouble(Double::doubleValue).sum();
    double loss = 0;
    for (int i = 0; i < penalties.length; i++)
      loss += (config.weights().get(i) / sum) * penalties[i];
    int unique = selection.size() - repeats;
    return new Metrics(
        mean,
        Arrays.stream(means).min().orElseThrow(),
        Arrays.stream(means).max().orElseThrow(),
        spread,
        deviation,
        repeats,
        unique / (double) bank.size(),
        unique / (double) selection.size(),
        topics.size(),
        allowedTopics.size(),
        averageRank,
        100 * (1 - loss));
  }
}
