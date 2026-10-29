package ru.exam.infrastructure;

import java.nio.file.Path;
import java.util.Locale;

public record LaunchOptions(Path dataDirectory, boolean checkOnly) {
  public static LaunchOptions parse(String[] args) {
    Path directory = null;
    boolean check = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--data-dir" -> {
          if (directory != null
              || i + 1 >= args.length
              || args[i + 1].isBlank()
              || args[i + 1].startsWith("--"))
            throw new IllegalArgumentException("После --data-dir укажите один каталог данных.");
          directory = Path.of(args[++i]);
        }
        case "--check" -> check = true;
        default ->
            throw new IllegalArgumentException(
                "Неизвестный параметр: " + args[i] + ". Допустимы --data-dir <каталог> и --check.");
      }
    }
    if (directory == null) {
      String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      Path user = Path.of(System.getProperty("user.home"));
      directory =
          os.contains("mac")
              ? user.resolve("Library/Application Support/ExamMaterials")
              : os.contains("win")
                  ? user.resolve("AppData/Local/ExamMaterials")
                  : user.resolve(".local/share/ExamMaterials");
    }
    return new LaunchOptions(directory.toAbsolutePath().normalize(), check);
  }
}
