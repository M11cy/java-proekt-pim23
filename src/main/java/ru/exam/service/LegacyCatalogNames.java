package ru.exam.service;

import java.util.HashSet;

/** Обновляет только известные старые подписи, сохраняя идентификаторы и материалы. */
public final class LegacyCatalogNames {
  private static final String OLD_CODE = "JAVA-DEMO";
  private static final String OLD_NAME = "Java: учебный пример";
  private static final String NAME = "Программирование на Java";
  private LegacyCatalogNames() {}

  public static void update(CatalogService service) {
    var subjects = service.subjects();
    var codes = new HashSet<String>();
    subjects.forEach(subject -> codes.add(subject.code()));
    for (var subject : subjects) {
      String code = subject.code();
      String name = subject.name();
      if (OLD_CODE.equals(code)) {
        code = "JAVA";
        int suffix = 1;
        while (codes.contains(code)) {
          code = "JAVA-PROGRAMMING" + (suffix == 1 ? "" : "-" + suffix);
          suffix++;
        }
      }
      if (OLD_NAME.equals(name)) name = NAME;
      if (!code.equals(subject.code()) || !name.equals(subject.name())) {
        service.renameSubject(subject.id(), code, name);
        codes.add(code);
      }
    }
  }

  /** Старые системные подписи читаются нейтрально; сохранённые события не переписываются. */
  public static String displayAuditDetails(String details) {
    if (details.startsWith(OLD_CODE + ": ")) {
      String name = details.substring(OLD_CODE.length() + 2);
      return OLD_NAME.equals(name) ? NAME : name;
    }
    return details;
  }
}
