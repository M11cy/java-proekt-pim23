package ru.exam.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import ru.exam.domain.User;
import ru.exam.infrastructure.*;

final class RememberedLogin {
  private static final int TOKEN_SIZE = 32;
  private static final Set<PosixFilePermission> OWNER_ACCESS =
      PosixFilePermissions.fromString("rw-------");
  private final Database database;
  private final Path file;

  RememberedLogin(Database database) {
    this.database = database;
    file = database.path().resolveSibling(database.path().getFileName() + ".session");
  }

  void save(User user) throws IOException {
    byte[] token = new byte[TOKEN_SIZE];
    new SecureRandom().nextBytes(token);
    try {
      String hash = hash(token);
      long expires = Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond();
      database.transaction(c -> {
        JdbcSupport.execute(c, """
            INSERT INTO remembered_login(id,user_id,password_version,token_hash,expires_at)
            VALUES(1,?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,
            password_version=excluded.password_version, token_hash=excluded.token_hash,
            expires_at=excluded.expires_at
            """, user.id(), user.passwordVersion(), hash, expires);
        return null;
      });
      write(token);
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  User resume() {
    byte[] token;
    try {
      if (!isPrivateFile()) return null;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        token = input.readNBytes(TOKEN_SIZE + 1);
      }
    } catch (IOException e) {
      return null;
    }
    try {
      if (token.length != TOKEN_SIZE) {
        deleteFile();
        return null;
      }
      String hash = hash(token);
      var user = database.transaction(c -> {
        var match = JdbcSupport.list(c, """
            SELECT u.id,u.login,u.name,u.role,u.active,u.password_version
            FROM remembered_login r JOIN users u ON u.id=r.user_id
            WHERE r.id=1 AND r.token_hash=? AND r.expires_at>?
              AND u.active=1 AND u.password_version=r.password_version
            """, AccessControl::map, hash, Instant.now().getEpochSecond());
        if (match.isEmpty()) return null;
        var account = match.getFirst();
        AuditLog.write(c, account.login(), "LOGIN", "user", account.id(), null, "SUCCESS",
            "Вход по сохранённому сеансу");
        return account;
      });
      if (user == null) deleteFile();
      return user;
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  static void revoke(Connection connection) throws SQLException {
    JdbcSupport.execute(connection, "DELETE FROM remembered_login");
  }

  void deleteFile() {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // The database revocation invalidates the token even if its local file cannot be deleted.
    }
  }

  private void write(byte[] token) throws IOException {
    boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
    Path staged = posix
        ? Files.createTempFile(file.getParent(), ".login-", ".session",
            PosixFilePermissions.asFileAttribute(OWNER_ACCESS))
        : Files.createTempFile(file.getParent(), ".login-", ".session");
    try {
      if (!posix) {
        var acl = Files.getFileAttributeView(staged, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Каталог не поддерживает закрытый файл сеанса.");
        var ownerAccess = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
            .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
        acl.setAcl(List.of(ownerAccess));
      }
      Files.write(staged, token);
      Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private boolean isPrivateFile() throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
    var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) return OWNER_ACCESS.containsAll(posix.readAttributes().permissions());
    var acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) return false;
    var owner = acl.getOwner();
    return acl.getAcl().stream().noneMatch(entry ->
        entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner));
  }

  private static String hash(byte[] token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
