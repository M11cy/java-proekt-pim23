package ru.exam.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Рабочая коллекция банка одной дисциплины, включая архивные вопросы. */
public final class QuestionBank {
  private final long subjectId;
  private final ArrayList<Question> questions = new ArrayList<>();

  public QuestionBank(long subjectId) {
    if (subjectId <= 0) throw new ValidationException("Выберите дисциплину.");
    this.subjectId = subjectId;
  }

  public void add(Question question) {
    Objects.requireNonNull(question);
    if (questions.stream().anyMatch(q -> q.normalizedText().equals(question.normalizedText())))
      throw new ValidationException(
          "Такой вопрос уже есть в дисциплине, включая архив. Измените текст или восстановите"
              + " существующий.");
    questions.add(question);
  }

  public List<Question> search(
      String text, Long topicId, QuestionType type, Integer difficulty, Boolean archived) {
    String needle = Question.normalize(text == null ? "" : text);
    return questions.stream()
        .filter(q -> q.normalizedText().contains(needle))
        .filter(q -> topicId == null || q.topicId() == topicId)
        .filter(q -> type == null || q.type() == type)
        .filter(q -> difficulty == null || q.difficulty() == difficulty)
        .filter(q -> archived == null || q.archived() == archived)
        .toList();
  }

  public List<Question> questions() {
    return List.copyOf(questions);
  }

  public long subjectId() {
    return subjectId;
  }
}
