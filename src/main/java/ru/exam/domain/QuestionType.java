package ru.exam.domain;

public enum QuestionType {
  THEORY("Теория"),
  PRACTICE("Практика");
  private final String label;

  QuestionType(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
