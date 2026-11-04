package ru.exam.service;

import ru.exam.domain.User;

public final class Session {
  private volatile User user;

  public User user() {
    return user;
  }

  void start(User value) {
    user = value;
  }

  public void clear() {
    user = null;
  }
}
