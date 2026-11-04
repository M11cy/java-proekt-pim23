package ru.exam.service;

import java.util.List;
import ru.exam.domain.*;
import ru.exam.repository.CatalogRepository;

public final class CatalogService {
  private final CatalogRepository repository;
  private final AccessControl access;

  public CatalogService(CatalogRepository repository, AccessControl access) {
    this.repository = repository;
    this.access = access;
  }

  public Subject createSubject(String code, String name) {
    access.admin();
    return repository.saveSubject(new Subject(0, code, name));
  }

  public Topic createTopic(long subjectId, String code, String name) {
    access.subject(subjectId);
    return repository.saveTopic(new Topic(0, subjectId, code, name));
  }

  public Subject renameSubject(long id, String code, String name) {
    access.admin();
    return repository.saveSubject(new Subject(id, code, name));
  }

  public Topic renameTopic(long id, long subjectId, String code, String name) {
    access.subject(subjectId);
    access.topic(id);
    return repository.saveTopic(new Topic(id, subjectId, code, name));
  }

  public List<Subject> subjects() {
    access.current();
    return repository.subjects();
  }

  public List<Topic> topics(long id) {
    access.subject(id);
    return repository.topics(id);
  }

  public Question save(Question q) {
    access.topic(q.topicId());
    if (q.id() > 0) access.question(q.id());
    return repository.saveQuestions(List.of(q)).getFirst();
  }

  public List<Question> questions(long subjectId) {
    access.subject(subjectId);
    return repository.questions(subjectId);
  }

  public List<Question> importQuestions(List<Question> questions) {
    if (questions == null || questions.isEmpty())
      throw new ValidationException("Нет вопросов для импорта.");
    if (questions.size() > 10_000) throw new ValidationException("В наборе более 10 000 вопросов.");
    if (questions.stream().anyMatch(q -> q.id() != 0 || q.revision() != 0))
      throw new ValidationException(
          "Импорт создаёт только новые вопросы и не перезаписывает существующие.");
    for (var q : questions) access.topic(q.topicId());
    return repository.saveQuestions(List.copyOf(questions));
  }

  public List<Revision> history(long id) {
    access.question(id);
    return repository.history(id);
  }

  public List<Question> bulkEdit(
      List<Question> selected, Integer difficulty, Integer importance, Boolean archived) {
    access.current();
    if (selected == null || selected.isEmpty() || selected.size() > 10000)
      throw new ValidationException("Выберите от 1 до 10 000 вопросов.");
    if (difficulty == null && importance == null && archived == null)
      throw new ValidationException("Выберите хотя бы одно изменение.");
    var ids = new java.util.HashSet<Long>();
    var values = new java.util.ArrayList<Question>();
    for (var q : selected) {
      if (q.id() <= 0 || !ids.add(q.id()))
        throw new ValidationException("Состав массовой операции некорректен.");
      access.question(q.id());
      access.topic(q.topicId());
      values.add(
          new Question(
              q.id(),
              q.topicId(),
              q.text(),
              q.type(),
              difficulty == null ? q.difficulty() : difficulty,
              importance == null ? q.importance() : importance,
              q.maxPoints(),
              q.guidance(),
              archived == null ? q.archived() : archived,
              q.revision()));
    }
    return repository.saveQuestions(values);
  }

  public List<AuditEntry> audit(AuditFilter filter) {
    access.current();
    return repository.audit(filter);
  }

  public List<AuditEntry> audit() {
    return audit(AuditFilter.all());
  }
}
