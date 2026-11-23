package ru.exam.ui;

import static ru.exam.domain.TicketSet.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import ru.exam.domain.*;
import ru.exam.service.*;

final class TicketPanel extends JPanel {
  private record Setup(List<Item> bank, List<Topic> topics) {}

  private final TicketService service;
  private final CatalogController controller;
  private final CatalogService catalog;
  private final OrganizationService organization;
  private final JComboBox<Subject> subjects = new JComboBox<>();
  private final DefaultListModel<TicketService.Summary> summaryModel = new DefaultListModel<>();
  private final JList<TicketService.Summary> summaries = new JList<>(summaryModel);
  private final JTextArea metrics = new JTextArea(7, 50);
  private final JTextArea fullQuestion = new JTextArea(3, 50);
  private final DefaultTableModel rows =
      new DefaultTableModel(
          new String[] {"Билет", "Позиция", "Вопрос", "Тема", "Тип", "Сложность"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return false;
        }

        @Override
        public Class<?> getColumnClass(int column) {
          return column < 2 || column == 5 ? Integer.class : String.class;
        }
      };
  private final JTable questions = new JTable(rows);
  private final JButton replace = SwingSupport.button("Заменить вопрос", this::replace);
  private final JButton improve = SwingSupport.button("Сбалансировать", this::improve);
  private final JButton approve = SwingSupport.button("Утвердить", this::approve);
  private final JButton export = SwingSupport.button("Экспорт Word", this::export);
  private TicketSet current;
  private boolean updating;

  TicketPanel(
      TicketService service,
      CatalogController controller,
      CatalogService catalog,
      OrganizationService organization) {
    super(new BorderLayout(5, 5));
    this.service = service;
    this.controller = controller;
    this.catalog = catalog;
    this.organization = organization;
    subjects.setRenderer(SwingSupport.literalListRenderer());
    summaries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    summaries.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            if (value instanceof TicketService.Summary s)
              value = s.name() + (s.approved() ? " (утверждён)" : " (черновик)");
            var label =
                (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.putClientProperty("html.disable", true);
            return label;
          }
        });
    add(
        SwingSupport.row(
            SwingSupport.label("Дисциплина"),
            subjects,
            SwingSupport.button("Создать комплект", this::generate),
            SwingSupport.button("Обновить", this::refresh),
            replace,
            improve,
            approve,
            export),
        BorderLayout.NORTH);
    SwingSupport.table(questions);
    questions.getColumnModel().getColumn(2).setPreferredWidth(360);
    metrics.setEditable(false);
    metrics.setLineWrap(true);
    metrics.setWrapStyleWord(true);
    fullQuestion.setEditable(false);
    fullQuestion.setLineWrap(true);
    fullQuestion.setWrapStyleWord(true);
    var detail = new JPanel(new BorderLayout(4, 4));
    detail.add(new JScrollPane(metrics), BorderLayout.NORTH);
    detail.add(new JScrollPane(questions));
    detail.add(new JScrollPane(fullQuestion), BorderLayout.SOUTH);
    var list = new JScrollPane(summaries);
    list.setPreferredSize(new Dimension(250, 320));
    add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, detail));
    subjects.addActionListener(
        e -> {
          if (!updating) refresh();
        });
    summaries.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !updating) {
            var selected = summaries.getSelectedValue();
            show(null);
            if (selected != null)
              controller.execute(
                  "Загрузка комплекта...", () -> service.load(selected.id()), this::show);
          }
        });
    questions
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              int index = selectedIndex();
              fullQuestion.setText(
                  current == null || index < 0
                      ? ""
                      : current.items().get(current.selected().get(index)).text());
              fullQuestion.setCaretPosition(0);
            });
    show(null);
  }

  void setSubjects(List<Subject> values) {
    var selected = (Subject) subjects.getSelectedItem();
    updating = true;
    subjects.removeAllItems();
    values.forEach(subjects::addItem);
    if (selected != null)
      values.stream()
          .filter(s -> s.id() == selected.id())
          .findFirst()
          .ifPresent(subjects::setSelectedItem);
    updating = false;
  }

  void refresh() {
    var subject = (Subject) subjects.getSelectedItem();
    if (subject == null) {
      summaryModel.clear();
      show(null);
      return;
    }
    var selected = summaries.getSelectedValue();
    controller.execute(
        "Загрузка комплектов...",
        () -> service.summaries(subject.id()),
        values -> {
          fillList(values, selected == null ? 0 : selected.id());
          var row = summaries.getSelectedValue();
          if (row == null) show(null);
          else
            controller.execute("Загрузка комплекта...", () -> service.load(row.id()), this::show);
        });
  }

  private void fillList(List<TicketService.Summary> values, long selected) {
    updating = true;
    summaryModel.clear();
    values.forEach(summaryModel::addElement);
    for (int i = 0; i < values.size(); i++)
      if (values.get(i).id() == selected) summaries.setSelectedIndex(i);
    if (summaries.isSelectionEmpty() && !values.isEmpty()) summaries.setSelectedIndex(0);
    updating = false;
  }

  private void generate() {
    var subject = (Subject) subjects.getSelectedItem();
    if (subject == null) return;
    controller.execute(
        "Загрузка банка для генерации...",
        () -> new Setup(service.bank(subject.id()), catalog.topics(subject.id())),
        value -> {
          if (value.bank().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Добавьте активные вопросы в банк дисциплины.");
            return;
          }
          var settings = new TicketSettings(value.bank(), value.topics());
          settings.open(
              this,
              config -> {
                String title = settings.title();
                controller.execute(
                    "Подбор и балансировка билетов...",
                    () -> service.generate(subject.id(), title, config),
                    this::updated);
              });
        });
  }

  private void updated(TicketSet set) {
    show(set);
    controller.execute(
        "Обновление списка комплектов...",
        () -> service.summaries(set.subjectId()),
        values -> fillList(values, set.id()));
  }

  private void show(TicketSet set) {
    current = set;
    rows.setRowCount(0);
    fullQuestion.setText("");
    boolean draft = set != null && !set.approved();
    replace.setEnabled(draft);
    improve.setEnabled(draft);
    approve.setEnabled(draft);
    export.setEnabled(set != null && set.approved());
    if (set == null) {
      metrics.setText("");
      return;
    }
    var items = set.items();
    int size = set.config().size();
    for (int i = 0; i < set.selected().size(); i++) {
      var q = items.get(set.selected().get(i));
      rows.addRow(
          new Object[] {
            i / size + 1,
            i % size + 1,
            q.text().replace('\n', ' '),
            q.topic(),
            q.type().toString(),
            q.difficulty()
          });
    }
    var optimizer = new TicketOptimizer(set.config(), set.bank());
    var before = optimizer.metrics(set.initial());
    var after = optimizer.metrics(set.selected());
    metrics.setText(
        set.name()
            + " ("
            + (draft ? "черновик" : "утверждён")
            + ")"
            + "\nИсходный вариант  "
            + summary(before)
            + "\nТекущий вариант  "
            + summary(after)
            + "\nЦель: "
            + set.config().target()
            + ". Сохранён лучший вариант, найденный в пределах поиска."
            + "\nДля сложности билетов используется сложность из карточки вопроса.");
    metrics.setCaretPosition(0);
  }

  private static String summary(TicketOptimizer.Metrics m) {
    return String.format(
        "Качество %.2f / 100 | Сложность %.2f (от %.2f до %.2f), разброс %.2f, отклонение от цели"
            + " %.2f%nПовторы %d | Уникальные места %.1f%% | Покрытие банка %.1f%% | Темы %d из %d"
            + " | Рейтинг %.2f",
        m.quality(),
        m.average(),
        m.minimum(),
        m.maximum(),
        m.spread(),
        m.deviation(),
        m.repeats(),
        100 * m.uniqueShare(),
        100 * m.bankCoverage(),
        m.coveredTopics(),
        m.availableTopics(),
        m.averageRank());
  }

  private int selectedIndex() {
    int row = questions.getSelectedRow();
    return row < 0 ? -1 : questions.convertRowIndexToModel(row);
  }

  private void replace() {
    var set = current;
    int index = selectedIndex();
    if (set == null || index < 0) {
      JOptionPane.showMessageDialog(this, "Выберите вопрос в таблице.");
      return;
    }
    var slot = set.config().slots().get(index % set.config().size());
    var choices =
        new JList<>(
            set.bank().stream()
                .filter(slot::accepts)
                .filter(q -> !set.config().excluded().contains(q.id()))
                .toArray(Item[]::new));
    choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    choices.setCellRenderer(SwingSupport.literalListRenderer());
    var scroll = new JScrollPane(choices);
    scroll.setPreferredSize(new Dimension(730, 250));
    var panel = new JPanel(new BorderLayout());
    panel.add(scroll);
    SwingSupport.edit(
        this,
        "Замена вопроса",
        panel,
        () -> {
          var choice = choices.getSelectedValue();
          if (choice == null) throw new ValidationException("Выберите вопрос на замену.");
          return choice.id();
        },
        id ->
            controller.execute(
                "Проверка замены...",
                () -> service.replace(set.id(), set.version(), index, id),
                this::show));
  }

  private void improve() {
    var set = current;
    if (set != null)
      controller.execute(
          "Балансировка...", () -> service.improve(set.id(), set.version()), this::show);
  }

  private void approve() {
    var set = current;
    if (set != null)
      controller.execute(
          "Загрузка учебных периодов...",
          () -> organization.snapshot().periods(),
          periods -> {
            var org = new JTextField(28);
            var period = new JComboBox<String>();
            period.setEditable(true);
            periods.forEach(p -> period.addItem(p.name()));
            var signature = new JTextField("Преподаватель", 28);
            var approval = new JTextField(28);
            var form =
                SwingSupport.form(
                    "Организация",
                    org,
                    "Учебный период",
                    period,
                    "Подпись",
                    signature,
                    "Дополнительная строка утверждения",
                    approval);
            SwingSupport.edit(
                this,
                "Утверждение комплекта и шапка Word",
                form,
                () ->
                    new Header(
                        org.getText(),
                        Objects.toString(period.getEditor().getItem(), ""),
                        signature.getText(),
                        approval.getText()),
                header -> {
                  if (JOptionPane.showConfirmDialog(
                          this,
                          "Зафиксировать комплект и шапку для повторного экспорта?",
                          "Утверждение",
                          JOptionPane.OK_CANCEL_OPTION)
                      == JOptionPane.OK_OPTION)
                    controller.execute(
                        "Проверка и утверждение...",
                        () -> service.approve(set.id(), set.version(), header),
                        this::updated);
                });
          });
  }

  private void export() {
    var set = current;
    if (set == null) return;
    var chooser = new JFileChooser();
    chooser.setDialogTitle("Экспорт экзаменационных билетов");
    chooser.setFileFilter(new FileNameExtensionFilter("Документ Word (*.docx)", "docx"));
    chooser.setSelectedFile(new java.io.File("Экзаменационные билеты.docx"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
      path = path.resolveSibling(path.getFileName() + ".docx");
    Path destination = path;
    controller.execute(
        "Экспорт Word...",
        () -> service.export(set.id(), destination),
        saved -> JOptionPane.showMessageDialog(this, "Документ сохранён.\n" + saved));
  }
}
