package ru.exam.repository;

import java.util.List;
import ru.exam.domain.*;

public interface CatalogRepository {
  List<Subject> subjects();

  Subject saveSubject(Subject subject);

  List<Topic> topics(long subjectId);

  Topic saveTopic(Topic topic);

  List<Question> questions(long subjectId);

  List<Question> saveQuestions(List<Question> questions);

  List<Revision> history(long questionId);

  List<AuditEntry> audit();
}
