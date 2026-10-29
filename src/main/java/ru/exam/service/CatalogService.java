package ru.exam.service;

import java.util.List;
import ru.exam.domain.*;
import ru.exam.repository.CatalogRepository;

public final class CatalogService {
  private final CatalogRepository repository;

  public CatalogService(CatalogRepository repository) {
    this.repository = repository;
  }

  public Subject createSubject(String code, String name) {
    return repository.saveSubject(new Subject(0, code, name));
  }

  public Topic createTopic(long subjectId, String code, String name) {
    return repository.saveTopic(new Topic(0, subjectId, code, name));
  }

  public Subject renameSubject(long id, String code, String name) {
    return repository.saveSubject(new Subject(id, code, name));
  }

  public Topic renameTopic(long id, long subjectId, String code, String name) {
    return repository.saveTopic(new Topic(id, subjectId, code, name));
  }

  public List<Subject> subjects() {
    return repository.subjects();
  }

  public List<Topic> topics(long id) {
    return repository.topics(id);
  }

  public Question save(Question q) {
    return repository.saveQuestions(List.of(q)).getFirst();
  }

  public List<Question> questions(long subjectId) {
    return repository.questions(subjectId);
  }

  public List<Question> importQuestions(List<Question> questions) {
    if (questions == null || questions.isEmpty())
      throw new ValidationException("Нет вопросов для импорта.");
    if (questions.size() > 10_000) throw new ValidationException("В наборе более 10 000 вопросов.");
    if (questions.stream().anyMatch(q -> q.id() != 0 || q.revision() != 0))
      throw new ValidationException(
          "Импорт создаёт только новые вопросы и не перезаписывает существующие.");
    return repository.saveQuestions(List.copyOf(questions));
  }

  public List<Revision> history(long id) {
    return repository.history(id);
  }

  public List<AuditEntry> audit() {
    return repository.audit();
  }
}
