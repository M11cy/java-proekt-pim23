package ru.exam.ui;

import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDate;
import javax.swing.*;
import ru.exam.service.*;

final class BackupPanel extends JPanel {
  private final BackupService service;
  private final CatalogController controller;
  private final Runnable restored;

  BackupPanel(BackupService service, CatalogController controller, Runnable restored) {
    super(new BorderLayout());
    this.service = service;
    this.controller = controller;
    this.restored = restored;
    add(
        SwingSupport.row(
            SwingSupport.button("Создать резервную копию", this::create),
            SwingSupport.button("Проверить копию", this::verify),
            SwingSupport.button("Восстановить из копии", this::restore)),
        BorderLayout.NORTH);
  }

  private JFileChooser chooser() {
    var chooser = new JFileChooser();
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("База SQLite (*.db)", "db"));
    return chooser;
  }

  private void create() {
    var chooser = chooser();
    chooser.setSelectedFile(new java.io.File("exam-materials-" + LocalDate.now() + ".db"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path target = chooser.getSelectedFile().toPath();
    controller.execute(
        "Создание и проверка резервной копии...",
        () -> service.create(target),
        saved -> JOptionPane.showMessageDialog(this, "Копия создана и проверена: " + saved));
  }

  private void verify() {
    var chooser = chooser();
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path source = chooser.getSelectedFile().toPath();
    controller.execute(
        "Проверка копии...",
        () -> {
          service.verify(source);
          return true;
        },
        ignored ->
            JOptionPane.showMessageDialog(this, "Целостность, связи и версия схемы проверены."));
  }

  private void restore() {
    var chooser = chooser();
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path source = chooser.getSelectedFile().toPath();
    if (JOptionPane.showConfirmDialog(
            this,
            "Заменить текущую базу содержимым "
                + source.getFileName()
                + "?\n"
                + "Перед заменой будет сохранена копия текущего состояния.\n"
                + "После восстановления потребуется вход с учётной записью из копии.",
            "Восстановление базы",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        != JOptionPane.YES_OPTION) return;
    controller.execute(
        "Проверка и восстановление базы...",
        () -> service.restore(source),
        saved -> {
          JOptionPane.showMessageDialog(
              this, "База восстановлена. Предыдущее состояние сохранено: " + saved);
          restored.run();
        });
  }
}
