package ru.exam.infrastructure;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import ru.exam.domain.ValidationException;

public final class PasswordHasher {
  private static final int ITERATIONS = 600_000;
  private static final int MIN_LENGTH = 4;
  private static final int MAX_LENGTH = 256;
  private final SecureRandom random = new SecureRandom();

  public String hash(char[] password) {
    int length = password == null ? 0 : Character.codePointCount(password, 0, password.length);
    if (length < MIN_LENGTH || length > MAX_LENGTH)
      throw new ValidationException("Пароль: от " + MIN_LENGTH + " до " + MAX_LENGTH + " символов.");
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return "pbkdf2-sha256$"
        + ITERATIONS
        + "$"
        + Base64.getEncoder().encodeToString(salt)
        + "$"
        + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
  }

  public boolean verify(char[] password, String encoded) {
    if (password == null || Character.codePointCount(password, 0, password.length) > MAX_LENGTH) return false;
    try {
      String[] parts = encoded.split("[$]");
      if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      if (iterations < ITERATIONS
          || iterations > 2_000_000
          || salt.length != 16
          || expected.length != 32) return false;
      byte[] actual = derive(password, salt, iterations);
      try {
        return MessageDigest.isEqual(expected, actual);
      } finally {
        Arrays.fill(actual, (byte) 0);
      }
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private byte[] derive(char[] password, byte[] salt, int iterations) {
    var spec = new PBEKeySpec(password, salt, iterations, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Алгоритм пароля недоступен.", e);
    } finally {
      spec.clearPassword();
    }
  }
}
