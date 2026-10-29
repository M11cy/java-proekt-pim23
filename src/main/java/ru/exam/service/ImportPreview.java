package ru.exam.service;

import java.util.List;
import ru.exam.domain.Question;

public record ImportPreview(List<Question> questions, List<String> errors) {
  public ImportPreview {
    questions = List.copyOf(questions);
    errors = List.copyOf(errors);
  }

  public boolean canImport() {
    return !questions.isEmpty() && errors.isEmpty();
  }
}
