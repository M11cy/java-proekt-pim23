package ru.exam;

import java.awt.GraphicsEnvironment;
import javax.swing.*;
import ru.exam.infrastructure.*;
import ru.exam.service.CatalogService;
import ru.exam.ui.MainFrame;

public final class App {
  private App() {}

  public static void main(String[] args) {
    System.setProperty("apple.awt.application.name", "Экзаменационные материалы");
    try {
      var options = LaunchOptions.parse(args);
      var lock = InstanceLock.acquire(options.dataDirectory());
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      lock.close();
                    } catch (Exception ignored) {
                    }
                  }));
      var database = new Database(options.dataDirectory().resolve("exam-materials.db"));
      database.initialize();
      if (options.checkOnly()) {
        String integrity =
            database.read(
                c -> {
                  try (var s = c.createStatement();
                      var r = s.executeQuery("PRAGMA integrity_check")) {
                    r.next();
                    return r.getString(1);
                  }
                });
        if (!"ok".equals(integrity))
          throw new IllegalStateException("Проверка целостности базы не пройдена.");
        System.out.println("База доступна. Целостность: ok. " + database.path());
        lock.close();
        return;
      }
      if (GraphicsEnvironment.isHeadless())
        throw new IllegalStateException(
            "Для окна приложения требуется графическая среда. Проверка базы: --check.");
      var service = new CatalogService(new JdbcCatalogRepository(database));
      ru.exam.service.LegacyCatalogNames.update(service);
      SwingUtilities.invokeLater(
          () -> {
            try {
              UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            UIManager.put("OptionPane.yesButtonText", "Да");
            UIManager.put("OptionPane.noButtonText", "Нет");
            UIManager.put("OptionPane.cancelButtonText", "Отмена");
            new MainFrame(service, database.path()).setVisible(true);
          });
    } catch (Exception e) {
      System.err.println(e.getMessage());
      if (!GraphicsEnvironment.isHeadless() && !java.util.Arrays.asList(args).contains("--check"))
        JOptionPane.showMessageDialog(
            null, e.getMessage(), "Не удалось запустить приложение", JOptionPane.ERROR_MESSAGE);
      System.exit(1);
    }
  }
}
