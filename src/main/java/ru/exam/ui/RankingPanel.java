package ru.exam.ui;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import ru.exam.domain.*;
import ru.exam.service.*;

final class RankingPanel extends JPanel {
  private final RankingService service;
  private final CatalogController controller;
  private final JComboBox<Subject> subjects = new JComboBox<>();
  private final JComboBox<RankingProfile> profiles = new JComboBox<>();
  private final JTextField name = new JTextField(16), horizon = new JTextField(5);
  private final JTextField[] weights = {
    new JTextField(4), new JTextField(4), new JTextField(4), new JTextField(4), new JTextField(4)
  };
  private final Runnable onCalculated;
  private final JTextArea explanation = new JTextArea(8, 70);
  private final DefaultTableModel model =
      new DefaultTableModel(
          new String[] {
            "ID", "Вопрос", "Рейтинг", "Расчётная сложность",
            "I", "F", "D", "A", "E", "Документов Word", "Ответов", "Давность"
          },
          0) {
        @Override
        public boolean isCellEditable(int r, int c) {
          return false;
        }

        @Override
        public Class<?> getColumnClass(int c) {
          return switch (c) {
            case 0, 9, 10 -> Long.class;
            case 3 -> Integer.class;
            case 2, 4, 5, 6, 7, 8 -> Double.class;
            default -> String.class;
          };
        }
      };
  private final JTable table = new JTable(model);
  private List<QuestionRanker.Result> rows = List.of();
  private String calculation = "";
  private boolean updating;

  RankingPanel(RankingService service, CatalogController controller, Runnable onCalculated) {
    super(new BorderLayout());
    this.service = service;
    this.controller = controller;
    this.onCalculated = onCalculated;
    subjects.setRenderer(SwingSupport.literalListRenderer());
    profiles.setRenderer(SwingSupport.literalListRenderer());
    var top = new JPanel(new GridLayout(0, 1));
    top.add(
        SwingSupport.row(
            SwingSupport.label("Дисциплина"),
            subjects,
            SwingSupport.label("Профиль"),
            profiles,
            SwingSupport.button("Обновить", this::refresh)));
    top.add(
        SwingSupport.row(
            SwingSupport.label("Название"),
            name,
            SwingSupport.label("Помечать старым через, дней"),
            horizon));
    top.add(
        SwingSupport.row(
            SwingSupport.label("Веса I"),
            weights[0],
            SwingSupport.label("F"),
            weights[1],
            SwingSupport.label("D"),
            weights[2],
            SwingSupport.label("A"),
            weights[3],
            SwingSupport.label("E"),
            weights[4],
            SwingSupport.button("Сохранить профиль / новую версию", this::saveProfile)));
    top.add(SwingSupport.row(SwingSupport.button("Рассчитать и сохранить", this::calculate)));
    add(top, BorderLayout.NORTH);
    SwingSupport.table(table);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    for (int i = 0; i < table.getColumnCount(); i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(i == 1 ? 300 : i == 3 ? 155 : 110);
    explanation.setEditable(false);
    explanation.setLineWrap(true);
    explanation.setWrapStyleWord(true);
    var split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(explanation));
    split.setResizeWeight(0.7);
    add(split);
    subjects.addActionListener(
        e -> {
          if (!updating) refresh();
        });
    profiles.addActionListener(
        e -> {
          if (!updating) applyProfile();
        });
    table.getSelectionModel().addListSelectionListener(e -> explain());
  }

  void setSubjects(List<Subject> values) {
    Long selected = subjects.getSelectedItem() instanceof Subject s ? s.id() : null;
    updating = true;
    subjects.removeAllItems();
    for (var s : values) {
      subjects.addItem(s);
      if (Objects.equals(selected, s.id())) subjects.setSelectedItem(s);
    }
    updating = false;
  }

  void refresh() {
    if (!(subjects.getSelectedItem() instanceof Subject subject)) {
      profiles.removeAllItems();
      clear();
      return;
    }
    Long selected = profiles.getSelectedItem() instanceof RankingProfile p ? p.id() : null;
    controller.execute(
        "Загрузка профилей...",
        () -> service.profiles(subject.id()),
        values -> {
          updating = true;
          profiles.removeAllItems();
          for (var p : values) {
            profiles.addItem(p);
            if (Objects.equals(selected, p.id())) profiles.setSelectedItem(p);
          }
          updating = false;
          applyProfile();
        });
  }

  private void applyProfile() {
    clear();
    if (!(profiles.getSelectedItem() instanceof RankingProfile p)) return;
    name.setText(p.name());
    horizon.setText("" + p.horizon());
    for (int i = 0; i < 5; i++) weights[i].setText("" + p.weights().get(i));
  }

  private RankingProfile readProfile() {
    if (!(subjects.getSelectedItem() instanceof Subject s))
      throw new ValidationException("Выберите дисциплину.");
    RankingProfile previous = (RankingProfile) profiles.getSelectedItem();
    String title = name.getText().strip();
    boolean same = previous != null && previous.name().equalsIgnoreCase(title);
    var values =
        Arrays.stream(weights)
            .map(w -> Double.parseDouble(w.getText().strip().replace(',', '.')))
            .toList();
    return new RankingProfile(
        same ? previous.id() : 0,
        s.id(),
        title,
        same ? previous.version() : 0,
        same ? previous.target() : 3,
        values,
        Integer.parseInt(horizon.getText().strip()),
        same ? previous.minimumSamples() : 10,
        same ? previous.warningThreshold() : 1.5);
  }

  private void saveProfile() {
    try {
      var value = readProfile();
      controller.execute(
          "Сохранение версии профиля...",
          () -> service.saveProfile(value),
          saved -> {
            controller.execute(
                "Загрузка профилей...",
                () -> service.profiles(saved.subjectId()),
                values -> {
                  updating = true;
                  profiles.removeAllItems();
                  values.forEach(profiles::addItem);
                  profiles.setSelectedItem(saved);
                  updating = false;
                  applyProfile();
                });
          });
    } catch (Exception e) {
      error(e);
    }
  }

  private void calculate() {
    try {
      var edited = readProfile();
      var selected = (RankingProfile) profiles.getSelectedItem();
      if (selected == null || !edited.equals(selected))
        throw new ValidationException("Сначала сохраните изменения профиля.");
      controller.execute(
          "Расчёт и сохранение рейтинга...",
          () -> service.calculate(selected.subjectId(), selected.id()),
          values -> {
            rows = values;
            calculation = selected + ", " + QuestionRanker.FORMULA + ". Результат сохранён в банке.";
            model.setRowCount(0);
            for (var r : rows) {
              var c = r.contributions();
              model.addRow(new Object[] {
                  r.question().id(), r.question().text(), r.score(), r.difficulty(),
                  c.get(0), c.get(1), c.get(2), c.get(3), c.get(4), r.uses(), r.answers(),
                  r.old() ? "Старый вопрос" : ""
              });
            }
            explanation.setText(calculation
                + "\nI: значимость. F: редкость использования в Word. D: заданная сложность."
                + "\nA: давность правки. E: расхождение по оценкам преподавателя."
                + "\nБез документов Word вклад F равен 0. Без оценок вклад E равен 0."
                + "\nРасчётная сложность = 1 + округление(рейтинг / 25).");
            onCalculated.run();
          });
    } catch (Exception e) {
      error(e);
    }
  }

  private QuestionRanker.Result selected() {
    int row = table.getSelectedRow();
    return row < 0 ? null : rows.get(table.convertRowIndexToModel(row));
  }

  private void explain() {
    var r = selected();
    if (r == null) return;
    explanation.setText(calculation + "\nВопрос №" + r.question().id()
        + "\nРейтинг: " + r.score() + ". Расчётная сложность: " + r.difficulty()
        + "\nВклады [I, F, D, A, E]: " + r.contributions()
        + "\nДокументов Word: " + r.uses() + ". Ответов этой версии: " + r.answers()
        + "\nРасхождение: " + (r.discrepancy() == null
            ? "нет оценок, вклад E равен 0" : r.discrepancy())
        + (r.old() ? "\nВопрос давно не редактировался." : ""));
  }

  private void clear() {
    rows = List.of();
    model.setRowCount(0);
    explanation.setText("");
  }

  private void error(Exception e) {
    JOptionPane.showMessageDialog(
        this, e.getMessage(), "Проверьте данные", JOptionPane.WARNING_MESSAGE);
  }
}
