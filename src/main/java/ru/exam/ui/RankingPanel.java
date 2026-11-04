package ru.exam.ui;

import java.awt.*;
import java.time.*;
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
  private final JTextField name = new JTextField(16),
      horizon = new JTextField(5),
      samples = new JTextField(5),
      threshold = new JTextField(4);
  private final JTextField[] weights = {
    new JTextField(4), new JTextField(4), new JTextField(4), new JTextField(4), new JTextField(4)
  };
  private final JSpinner target = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
  private final JTextField date = new JTextField(LocalDate.now(ZoneOffset.UTC).toString(), 10);
  private final JCheckBox synthetic = new JCheckBox("Включать синтетические данные");
  private final JTextArea explanation = new JTextArea(8, 70);
  private final DefaultTableModel model =
      new DefaultTableModel(
          new String[] {
            "ID",
            "Вопрос",
            "Рейтинг",
            "I: вклад",
            "F: вклад",
            "D: вклад",
            "A: вклад",
            "E: вклад",
            "Использован",
            "Ответов",
            "Наблюдаемая сложность",
            "Предупреждение"
          },
          0) {
        @Override
        public boolean isCellEditable(int r, int c) {
          return false;
        }

        @Override
        public Class<?> getColumnClass(int c) {
          return c == 0 || c == 8 || c == 9
              ? Long.class
              : c >= 2 && c <= 7 || c == 10 ? Double.class : String.class;
        }
      };
  private final JTable table = new JTable(model);
  private List<QuestionRanker.Result> rows = List.of();
  private String calculation = "";
  private boolean updating;

  RankingPanel(RankingService service, CatalogController controller) {
    super(new BorderLayout());
    this.service = service;
    this.controller = controller;
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
            SwingSupport.label("Целевая сложность"),
            target,
            SwingSupport.label("Период, дней"),
            horizon,
            SwingSupport.label("Минимум ответов"),
            samples,
            SwingSupport.label("Порог"),
            threshold));
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
    top.add(
        SwingSupport.row(
            SwingSupport.label("Дата расчёта (UTC)"),
            date,
            synthetic,
            SwingSupport.button("Рассчитать", this::calculate)));
    top.add(
        SwingSupport.row(
            SwingSupport.button("Добавить наблюдения", this::addObservation),
            SwingSupport.button("История наблюдений", this::observations),
            SwingSupport.button("Подтвердить пересмотр", this::review)));
    add(top, BorderLayout.NORTH);
    SwingSupport.table(table);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    for (int i = 0; i < table.getColumnCount(); i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(i == 1 ? 300 : i == 11 ? 320 : 110);
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
    target.setValue(p.target());
    horizon.setText("" + p.horizon());
    samples.setText("" + p.minimumSamples());
    threshold.setText("" + p.warningThreshold());
    for (int i = 0; i < 5; i++) weights[i].setText("" + p.weights().get(i));
  }

  private RankingProfile readProfile() throws java.text.ParseException {
    if (!(subjects.getSelectedItem() instanceof Subject s))
      throw new ValidationException("Выберите дисциплину.");
    target.commitEdit();
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
        (Integer) target.getValue(),
        values,
        Integer.parseInt(horizon.getText().strip()),
        Integer.parseInt(samples.getText().strip()),
        Double.parseDouble(threshold.getText().strip().replace(',', '.')));
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
      LocalDate at = LocalDate.parse(date.getText().strip());
      boolean include = synthetic.isSelected();
      controller.execute(
          "Расчёт рейтинга...",
          () -> service.calculate(selected.subjectId(), selected.id(), at, include),
          values -> {
            rows = values;
            calculation =
                selected
                    + "; "
                    + RankingProfile.FORMULA
                    + "; дата UTC: "
                    + at
                    + "; синтетические: "
                    + include;
            model.setRowCount(0);
            for (var r : rows) {
              var c = r.contributions();
              model.addRow(
                  new Object[] {
                    r.question().id(),
                    r.question().text(),
                    r.score(),
                    c.get(0),
                    c.get(1),
                    c.get(2),
                    c.get(3),
                    c.get(4),
                    r.uses(),
                    r.attempts(),
                    r.observedDifficulty(),
                    r.warning() + (r.synthetic() ? "; содержит синтетические данные" : "")
                  });
            }
            explanation.setText(
                calculation
                    + "\nВопросов: "
                    + rows.size()
                    + "\n"
                    + "I: значимость; F: редкость использования; D: соответствие целевой сложности;"
                    + " A: актуальность; E: потребность в проверке сложности.\n"
                    + "Рейтинг равен сумме пяти вкладов. Веса нормируются до суммы 1.");
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
    explanation.setText(
        calculation
            + "\nВопрос №"
            + r.question().id()
            + ", v"
            + r.question().revision()
            + "\nR = 100 * (wI*I + wF*F + wD*D + wA*A + wE*E) = "
            + r.score()
            + "\nПоказатели [I, F, D, A, E]: "
            + r.factors()
            + "\nВклады в баллах: "
            + r.contributions()
            + "\nИспользований: "
            + r.uses()
            + "; ответов этой ревизии: "
            + r.attempts()
            + "; наблюдаемая сложность: "
            + (r.observedDifficulty() == null ? "нет достаточной выборки" : r.observedDifficulty())
            + "\n"
            + r.warning()
            + (r.synthetic() ? "\nВ расчёте есть синтетические данные." : ""));
  }

  private void addObservation() {
    var selected = selected();
    if (selected == null) {
      error(new ValidationException("Рассчитайте рейтинг и выберите вопрос."));
      return;
    }
    var at = new JTextField(LocalDate.now(ZoneOffset.UTC).toString(), 12);
    var uses = new JTextField("0", 12);
    var attempts = new JTextField("0", 12);
    var ratio = new JTextField("0", 12);
    var generated = new JCheckBox("Синтетические данные");
    SwingSupport.edit(
        this,
        "Ручной ввод наблюдений: вопрос №" + selected.question().id(),
        SwingSupport.form(
            "Дата (UTC, ГГГГ-ММ-ДД)",
            at,
            "Число использований",
            uses,
            "Число оценённых ответов",
            attempts,
            "Сумма долей (баллы / максимум)",
            ratio,
            "Происхождение",
            generated),
        () ->
            new RankingObservation(
                UUID.randomUUID().toString(),
                selected.question().id(),
                selected.question().revision(),
                LocalDate.parse(at.getText().strip()),
                Integer.parseInt(uses.getText().strip()),
                Integer.parseInt(attempts.getText().strip()),
                Double.parseDouble(ratio.getText().strip().replace(',', '.')),
                "MANUAL",
                generated.isSelected()),
        observation ->
            controller.execute(
                "Сохранение наблюдения...",
                () -> {
                  service.recordObservation(observation);
                  return true;
                },
                ignored -> calculate()));
  }

  private void observations() {
    var r = selected();
    if (r == null) return;
    controller.execute(
        "Загрузка наблюдений...",
        () -> service.observations(r.question().id()),
        values -> {
          var text =
              new StringBuilder("Вопрос №" + r.question().id() + ". Все ревизии и источники.\n");
          for (var o : values)
            text.append(o.occurredOn())
                .append("; v")
                .append(o.revision())
                .append("; использований=")
                .append(o.uses())
                .append("; ответов=")
                .append(o.attempts())
                .append("; сумма долей=")
                .append(o.ratioSum())
                .append("; ")
                .append(o.source())
                .append(o.synthetic() ? "; СИНТЕТИЧЕСКИЕ" : "")
                .append("; ключ=")
                .append(o.eventKey())
                .append('\n');
          SwingSupport.showText(this, "Наблюдения", text.toString());
        });
  }

  private void review() {
    var r = selected();
    if (r == null) return;
    if (JOptionPane.showConfirmDialog(
            this,
            "Подтвердить актуальность вопроса №" + r.question().id() + " на сегодняшнюю дату UTC?",
            "Пересмотр",
            JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) return;
    controller.execute(
        "Подтверждение пересмотра...",
        () -> {
          service.confirmReview(r.question().id());
          return true;
        },
        ignored -> calculate());
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
