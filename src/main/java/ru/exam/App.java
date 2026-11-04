package ru.exam;

import java.awt.GraphicsEnvironment;
import javax.swing.*;
import ru.exam.infrastructure.*;
import ru.exam.service.*;
import ru.exam.ui.LoginDialog;
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
      var session = new Session();
      var access = new AccessControl(database, session);
      var auth = new AuthService(database, session, access);
      var service = new CatalogService(new JdbcCatalogRepository(database, access), access);
      var organization = new OrganizationService(database, access);
      var ranking = new RankingService(database, access, service);
      var backups = new BackupService(database, access, session);
      boolean setup = !auth.hasUsers();
      SwingUtilities.invokeLater(
          () -> {
            try {
              UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            UIManager.put("OptionPane.yesButtonText", "Да");
            UIManager.put("OptionPane.noButtonText", "Нет");
            UIManager.put("OptionPane.cancelButtonText", "Отмена");
            var open =
                new java.util.function.Consumer<Boolean>() {
                  @Override
                  public void accept(Boolean first) {
                    var dialog =
                        new LoginDialog(
                            auth,
                            first,
                            () ->
                                new MainFrame(
                                        service,
                                        database.path(),
                                        auth,
                                        access,
                                        organization,
                                        ranking,
                                        backups,
                                        () -> accept(false))
                                    .setVisible(true));
                    dialog.setVisible(true);
                  }
                };
            open.accept(setup);
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
