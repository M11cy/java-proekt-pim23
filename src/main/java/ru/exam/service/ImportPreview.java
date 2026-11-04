package ru.exam.service;

import java.util.List;
import ru.exam.domain.Question;

public record ImportPreview(List<Question> questions, List<String> errors, List<String> skipped) {
  public ImportPreview {
    questions = List.copyOf(questions);
    errors = List.copyOf(errors);
    skipped = List.copyOf(skipped);
  }

  public boolean canImport() {
    return !questions.isEmpty() && errors.isEmpty();
  }
}
