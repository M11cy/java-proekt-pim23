package ru.exam.domain;

public record User(
    long id, String login, String name, Role role, boolean active, int passwordVersion) {
  public enum Role {
    ADMIN("Администратор"),
    TEACHER("Преподаватель");
    private final String label;

    Role(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  @Override
  public String toString() {
    return name + " (" + login + ")";
  }
}
