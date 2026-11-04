package ru.exam.service;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import ru.exam.domain.*;
import ru.exam.infrastructure.*;

public final class AuthService {
  private final Database database;
  private final Session session;
  private final AccessControl access;
  private final PasswordHasher passwords = new PasswordHasher();
  private final String dummyHash = passwords.hash("unavailable-account-password".toCharArray());

  public AuthService(Database database, Session session, AccessControl access) {
    this.database = database;
    this.session = session;
    this.access = access;
  }

  private String loginName(String login) {
    String value = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
    if (!value.matches("[a-z0-9][a-z0-9._-]{2,39}"))
      throw new ValidationException(
          "Логин: 3..40 латинских букв, цифр, точек, дефисов или подчёркиваний.");
    return value;
  }

  public boolean hasUsers() {
    return database.read(
        c -> {
          try (var s = c.createStatement();
              var r = s.executeQuery("SELECT count(*) FROM users")) {
            return r.next() && r.getInt(1) > 0;
          }
        });
  }

  public User bootstrap(String login, String name, char[] password) {
    try {
      String normalized = loginName(login), display = Organization.text(name, "ФИО", 200);
      String hash = passwords.hash(password);
      User user =
          database.transaction(
              c -> {
                try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT count(*) FROM users")) {
                  if (!r.next() || r.getInt(1) != 0)
                    throw new ValidationException("Администратор уже создан. Выполните вход.");
                }
                try (var s =
                    c.prepareStatement(
                        "INSERT INTO users(login,name,role,active,password_hash)"
                            + " VALUES(?,?,'ADMIN',1,?)")) {
                  s.setString(1, normalized);
                  s.setString(2, display);
                  s.setString(3, hash);
                  s.executeUpdate();
                }
                User created = byLogin(c, normalized);
                AuditLog.write(
                    c,
                    normalized,
                    "CREATE_ADMIN",
                    "user",
                    created.id(),
                    null,
                    "SUCCESS",
                    "Создан первый администратор");
                return created;
              });
      session.start(user);
      return user;
    } finally {
      clear(password);
    }
  }

  public User login(String login, char[] password) {
    try {
      String normalized = loginName(login);
      User user =
          database.transaction(
              c -> {
                User found = byLogin(c, normalized);
                String hash = dummyHash, locked = null;
                int attempts = 0;
                if (found != null) {
                  try (var s =
                      c.prepareStatement(
                          "SELECT password_hash,failed_attempts,locked_until FROM users WHERE"
                              + " id=?")) {
                    s.setLong(1, found.id());
                    try (var r = s.executeQuery()) {
                      r.next();
                      hash = r.getString(1);
                      attempts = r.getInt(2);
                      locked = r.getString(3);
                    }
                  }
                }
                boolean blocked = locked != null && Instant.parse(locked).isAfter(Instant.now());
                boolean valid = passwords.verify(password, hash);
                if (found == null || !found.active() || blocked || !valid) {
                  if (found != null && !blocked) {
                    int failures = (locked != null ? 0 : attempts) + 1;
                    try (var s =
                        c.prepareStatement(
                            "UPDATE users SET failed_attempts=?,locked_until=? WHERE id=?")) {
                      s.setInt(1, failures);
                      s.setString(
                          2, failures >= 5 ? Instant.now().plusSeconds(300).toString() : null);
                      s.setLong(3, found.id());
                      s.executeUpdate();
                    }
                  }
                  AuditLog.write(
                      c,
                      normalized,
                      "LOGIN",
                      "user",
                      found == null ? 0 : found.id(),
                      null,
                      "DENIED",
                      "Вход отклонён");
                  return null;
                }
                try (var s =
                    c.prepareStatement(
                        "UPDATE users SET failed_attempts=0,locked_until=NULL WHERE id=?")) {
                  s.setLong(1, found.id());
                  s.executeUpdate();
                }
                AuditLog.write(
                    c, normalized, "LOGIN", "user", found.id(), null, "SUCCESS", "Вход в систему");
                return found;
              });
      if (user == null)
        throw new ValidationException(
            "Вход отклонён. Проверьте данные. После 5 ошибок повторите через 5 минут.");
      session.start(user);
      return user;
    } finally {
      clear(password);
    }
  }

  public List<User> users() {
    return database.read(
        c -> {
          access.admin(c);
          var result = new ArrayList<User>();
          try (var s = c.createStatement();
              var r =
                  s.executeQuery(
                      "SELECT id,login,name,role,active,password_version FROM users ORDER BY"
                          + " name,id")) {
            while (r.next()) result.add(AccessControl.map(r));
          }
          return List.copyOf(result);
        });
  }

  public User saveUser(
      long id, String login, String name, User.Role role, boolean active, char[] password) {
    try {
      String normalized = loginName(login), display = Organization.text(name, "ФИО", 200);
      if (role == null || id < 0)
        throw new ValidationException("Проверьте роль и ID пользователя.");
      String hash = password == null && id > 0 ? null : passwords.hash(password);
      User saved =
          database.transaction(
              c -> {
                User actor = access.admin(c);
                User before = id == 0 ? null : byId(c, id);
                if (id > 0 && before == null)
                  throw new ValidationException("Пользователь не найден.");
                if (id == actor.id() && (!active || role != User.Role.ADMIN))
                  throw new ValidationException(
                      "Нельзя отключить или понизить собственную учётную запись. Используйте"
                          + " другого администратора.");
                try (var s = c.prepareStatement("SELECT id FROM users WHERE login=? AND id<>?")) {
                  s.setString(1, normalized);
                  s.setLong(2, id);
                  try (var r = s.executeQuery()) {
                    if (r.next()) throw new ValidationException("Логин уже используется.");
                  }
                }
                if (id > 0 && !before.login().equals(normalized))
                  throw new ValidationException("Логин существующего пользователя не изменяется.");
                String sql =
                    id == 0
                        ? "INSERT INTO users(login,name,role,active,password_hash)"
                              + " VALUES(?,?,?,?,?)"
                        : "UPDATE users SET"
                              + " name=?,role=?,active=?,password_hash=coalesce(?,password_hash),password_version=password_version+1,failed_attempts=0,locked_until=NULL"
                              + " WHERE id=?";
                try (var s = c.prepareStatement(sql)) {
                  if (id == 0) {
                    s.setString(1, normalized);
                    s.setString(2, display);
                    s.setString(3, role.name());
                    s.setBoolean(4, active);
                    s.setString(5, hash);
                  } else {
                    s.setString(1, display);
                    s.setString(2, role.name());
                    s.setBoolean(3, active);
                    s.setString(4, hash);
                    s.setLong(5, id);
                  }
                  s.executeUpdate();
                }
                try (var s = c.createStatement();
                    var r =
                        s.executeQuery(
                            "SELECT count(*) FROM users WHERE active=1 AND role='ADMIN'")) {
                  if (!r.next() || r.getInt(1) == 0)
                    throw new ValidationException("Нельзя отключить последнего администратора.");
                }
                User value = byLogin(c, normalized);
                String details =
                    (before == null
                            ? "Новый пользователь"
                            : before.name() + ", " + before.role() + ", активен=" + before.active())
                        + " -> "
                        + value.name()
                        + ", "
                        + value.role()
                        + ", активен="
                        + value.active()
                        + (hash == null ? "" : "; пароль установлен");
                AuditLog.write(
                    c, actor.login(), "SAVE_USER", "user", value.id(), null, "SUCCESS", details);
                return value;
              });
      if (session.user() != null && session.user().id() == saved.id()) session.start(saved);
      return saved;
    } finally {
      clear(password);
    }
  }

  public void changePassword(char[] current, char[] next) {
    try {
      String hash = passwords.hash(next);
      User saved =
          database.transaction(
              c -> {
                User actor = access.require(c);
                try (var s = c.prepareStatement("SELECT password_hash FROM users WHERE id=?")) {
                  s.setLong(1, actor.id());
                  try (var r = s.executeQuery()) {
                    r.next();
                    if (!passwords.verify(current, r.getString(1)))
                      throw new ValidationException("Текущий пароль неверен.");
                  }
                }
                try (var s =
                    c.prepareStatement(
                        "UPDATE users SET password_hash=?,password_version=password_version+1 WHERE"
                            + " id=?")) {
                  s.setString(1, hash);
                  s.setLong(2, actor.id());
                  s.executeUpdate();
                }
                AuditLog.write(
                    c,
                    actor.login(),
                    "CHANGE_PASSWORD",
                    "user",
                    actor.id(),
                    null,
                    "SUCCESS",
                    "Изменён собственный пароль");
                return byId(c, actor.id());
              });
      session.start(saved);
    } finally {
      clear(current);
      clear(next);
    }
  }

  public void logout() {
    try {
      if (session.user() != null)
        database.transaction(
            c -> {
              var actor = session.user();
              AuditLog.write(
                  c,
                  actor.login(),
                  "LOGOUT",
                  "user",
                  actor.id(),
                  null,
                  "SUCCESS",
                  "Выход из системы");
              return null;
            });
    } finally {
      session.clear();
    }
  }

  private User byLogin(Connection c, String login) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT id,login,name,role,active,password_version FROM users WHERE login=?")) {
      s.setString(1, login);
      try (var r = s.executeQuery()) {
        return r.next() ? AccessControl.map(r) : null;
      }
    }
  }

  private User byId(Connection c, long id) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT id,login,name,role,active,password_version FROM users WHERE id=?")) {
      s.setLong(1, id);
      try (var r = s.executeQuery()) {
        return r.next() ? AccessControl.map(r) : null;
      }
    }
  }

  private static void clear(char[] value) {
    if (value != null) Arrays.fill(value, '\0');
  }
}
