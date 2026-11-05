package ru.exam.ui;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import ru.exam.domain.*;
import ru.exam.service.*;

public final class MainFrame extends JFrame {
  private final CatalogController controller;
  private final CatalogService service;
  private final AuthService auth;
  private final boolean admin;
  private final Runnable onLogout;
  private final RankingPanel ranking;
  private final Map<Component, Boolean> enabledBefore = new IdentityHashMap<>();
  private final JTextField auditFrom = new JTextField(10),
      auditUntil = new JTextField(10),
      auditSearch = new JTextField(16);
  private static final String[] AUDIT_ACTIONS = {
    "",
    "LOGIN",
    "LOGOUT",
    "CREATE_ADMIN",
    "SAVE_USER",
    "CHANGE_PASSWORD",
    "SAVE_SUBJECT",
    "SAVE_TOPIC",
    "CREATE_QUESTION",
    "REVISE_QUESTION",
    "SAVE_GROUP",
    "SAVE_STUDENT",
    "SAVE_PERIOD",
    "SAVE_ASSIGNMENT",
    "SAVE_RANKING_PROFILE",
    "CALCULATE_RANKING",
    "ADD_OBSERVATION",
    "REVIEW_QUESTION",
    "CREATE_BACKUP",
    "RESTORE_BACKUP"
  };
  private static final String[] AUDIT_OBJECTS = {
    "",
    "user",
    "subject",
    "topic",
    "question",
    "group",
    "student",
    "period",
    "assignment",
    "ranking_profile",
    "backup"
  };
  private final JComboBox<String> auditAction = new JComboBox<>(), auditObject = new JComboBox<>();
  private CatalogController.Snapshot data =
      new CatalogController.Snapshot(List.of(), List.of(), Map.of(), Map.of(), List.of());
  private final JComboBox<Subject> subject = new JComboBox<>();
  private final JComboBox<Object> topic = new JComboBox<>();
  private final JComboBox<Object> type =
      new JComboBox<>(new Object[] {"Все типы", QuestionType.THEORY, QuestionType.PRACTICE});
  private final JComboBox<Object> difficulty =
      new JComboBox<>(new Object[] {"Любая сложность", 1, 2, 3, 4, 5});
  private final JComboBox<String> archive =
      new JComboBox<>(new String[] {"Активные", "Архивные", "Все статусы"});
  private final JTextField search = new JTextField(18);
  private final JLabel count = SwingSupport.label("Загрузка...");
  private final JLabel status = SwingSupport.label(" ");
  private final QuestionModel questionModel = new QuestionModel();
  private final JTable questionsTable = new JTable(questionModel);
  private final JTextArea details = new JTextArea(5, 50);
  private final DefaultListModel<Subject> subjectListModel = new DefaultListModel<>();
  private final DefaultListModel<Topic> topicListModel = new DefaultListModel<>();
  private final JList<Subject> subjectList = new JList<>(subjectListModel);
  private final JList<Topic> topicList = new JList<>(topicListModel);
  private final DefaultTableModel auditModel =
      readOnlyModel(
          new String[] {
            "Дата и время", "Пользователь", "Действие", "Объект", "ID", "Результат", "Описание"
          });
  private boolean updating;
  private boolean busy;
  private Long preferredSubject;

  public MainFrame(
      CatalogService service,
      Path database,
      AuthService auth,
      AccessControl access,
      OrganizationService organization,
      RankingService rankingService,
      BackupService backups,
      Runnable onLogout) {
    super("Экзаменационные материалы");
    this.service = service;
    this.auth = auth;
    this.onLogout = onLogout;
    var user = access.current();
    admin = user.role() == User.Role.ADMIN;
    subject.setRenderer(SwingSupport.literalListRenderer());
    topic.setRenderer(SwingSupport.literalListRenderer());
    subjectList.setCellRenderer(SwingSupport.literalListRenderer());
    topicList.setCellRenderer(SwingSupport.literalListRenderer());
    controller = new CatalogController(service, rankingService, this::setBusy, status::setText, this::showError);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setMinimumSize(new Dimension(1050, 660));
    setSize(1180, 760);
    setLocationRelativeTo(null);
    var tabs = new JTabbedPane();
    tabs.addTab("Банк вопросов", bankPanel());
    tabs.addTab("Дисциплины и темы", catalogPanel());
    tabs.addTab("Журнал изменений", auditPanel());
    var organizationPanel = new OrganizationPanel(organization, auth, service, controller, admin);
    ranking = new RankingPanel(rankingService, controller, this::reload);
    tabs.addTab("Учебная организация", organizationPanel);
    tabs.addTab("Ранжирование", ranking);
    if (admin)
      tabs.addTab(
          "Резервные копии",
          new BackupPanel(
              backups,
              controller,
              () -> {
                dispose();
                onLogout.run();
              }));
    tabs.addChangeListener(
        e -> {
          if (tabs.getSelectedComponent() == organizationPanel) organizationPanel.refresh();
          if (tabs.getSelectedComponent() == ranking) ranking.refresh();
        });
    add(
        SwingSupport.row(
            SwingSupport.label(user.name() + " (" + user.login() + ", " + user.role() + ")"),
            action("Сменить пароль", this::changePassword),
            action("Выйти", this::logout)),
        BorderLayout.NORTH);
    var footer = new JPanel(new GridLayout(2, 1, 0, 4));
    footer.add(status);
    var location = SwingSupport.label("Локальная база: " + database.toAbsolutePath());
    footer.add(location);
    add(tabs, BorderLayout.CENTER);
    add(footer, BorderLayout.SOUTH);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (busy) {
              JOptionPane.showMessageDialog(
                  MainFrame.this, "Дождитесь завершения текущей операции.");
              return;
            }
            controller.execute(
                "Завершение сеанса...",
                () -> {
                  auth.logout();
                  return true;
                },
                ignored -> dispose());
          }
        });
    if (admin)
      controller.execute(
          "Обновление справочников...",
          () -> {
            LegacyCatalogNames.update(service);
            return true;
          },
          ignored -> reload());
    else reload();
  }

  private JButton action(String text, Runnable task) {
    return SwingSupport.button(text, task);
  }

  private JPanel bankPanel() {
    var root = new JPanel(new BorderLayout(0, 10));
    var filters = new JPanel(new GridLayout(3, 1, 0, 4));
    subject.setPreferredSize(new Dimension(320, subject.getPreferredSize().height));
    topic.setPreferredSize(new Dimension(360, topic.getPreferredSize().height));
    search.getAccessibleContext().setAccessibleName("Поиск по тексту вопроса");
    filters.add(
        SwingSupport.row(
            SwingSupport.label("Дисциплина"), subject, SwingSupport.label("Тема"), topic));
    filters.add(
        SwingSupport.row(
            SwingSupport.label("Поиск"),
            search,
            type,
            difficulty,
            archive,
            SwingSupport.button("Сбросить", this::resetFilters)));
    filters.add(
        SwingSupport.row(
            action("Новый вопрос", () -> edit(null)),
            action("Изменить", this::editSelected),
            action("В архив / восстановить", this::toggleArchive),
            action("Массовая правка", this::bulkEdit),
            action("История версий", this::history),
            action("Импорт CSV", this::chooseCsv),
            action("Обновить", this::reload)));
    SwingSupport.table(questionsTable);
    questionsTable.getColumnModel().getColumn(9).setCellRenderer(new DefaultTableCellRenderer() {
      @Override
      protected void setValue(Object value) {
        super.setValue(value);
        setHorizontalAlignment(SwingConstants.CENTER);
        setForeground(new Color(180, 0, 0));
        setToolTipText("!".equals(value) ? "Вопрос давно не редактировался" : null);
      }
    });
    questionsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    questionsTable.getColumnModel().getColumn(0).setPreferredWidth(45);
    questionsTable.getColumnModel().getColumn(1).setPreferredWidth(490);
    questionsTable.getSelectionModel().addListSelectionListener(e -> updateDetails());
    questionsTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) editSelected();
          }
        });
    details.setEditable(false);
    details.setLineWrap(true);
    details.setWrapStyleWord(true);
    var split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, new JScrollPane(questionsTable), new JScrollPane(details));
    split.setResizeWeight(0.78);
    var top = new JPanel(new BorderLayout());
    top.add(filters, BorderLayout.CENTER);
    top.add(count, BorderLayout.SOUTH);
    root.add(top, BorderLayout.NORTH);
    root.add(split, BorderLayout.CENTER);
    subject.addActionListener(
        e -> {
          if (!updating) {
            updateTopicFilter();
            applyFilters();
          }
        });
    topic.addActionListener(e -> applyFilters());
    type.addActionListener(e -> applyFilters());
    difficulty.addActionListener(e -> applyFilters());
    archive.addActionListener(e -> applyFilters());
    search
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                applyFilters();
              }

              public void removeUpdate(DocumentEvent e) {
                applyFilters();
              }

              public void changedUpdate(DocumentEvent e) {
                applyFilters();
              }
            });
    return root;
  }

  private JPanel catalogPanel() {
    var root = new JPanel(new BorderLayout(0, 10));
    var lists = new JPanel(new GridLayout(1, 2, 12, 0));
    subjectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    var left = new JPanel(new BorderLayout(8, 8));
    left.add(SwingSupport.label("Дисциплины"), BorderLayout.NORTH);
    left.add(new JScrollPane(subjectList));
    if (admin)
      left.add(
          SwingSupport.row(
              action("Добавить дисциплину", () -> subjectEditor(null)),
              action(
                  "Переименовать",
                  () -> {
                    if (subjectList.getSelectedValue() != null)
                      subjectEditor(subjectList.getSelectedValue());
                  })),
          BorderLayout.SOUTH);
    var right = new JPanel(new BorderLayout(8, 8));
    right.add(SwingSupport.label("Темы выбранной дисциплины"), BorderLayout.NORTH);
    right.add(new JScrollPane(topicList));
    right.add(
        SwingSupport.row(
            action("Добавить тему", () -> topicEditor(null)),
            action(
                "Переименовать",
                () -> {
                  if (topicList.getSelectedValue() != null)
                    topicEditor(topicList.getSelectedValue());
                })),
        BorderLayout.SOUTH);
    subjectList.addListSelectionListener(e -> updateCatalogTopics());
    lists.add(left);
    lists.add(right);
    root.add(lists, BorderLayout.CENTER);
    return root;
  }

  private JPanel auditPanel() {
    var root = new JPanel(new BorderLayout(8, 8));
    var table = new JTable(auditModel);
    SwingSupport.table(table);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    for (int i = 0; i < table.getColumnCount(); i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(i == 6 ? 700 : 160);
    auditAction.addItem("Все действия");
    for (int i = 1; i < AUDIT_ACTIONS.length; i++)
      auditAction.addItem(actionLabel(AUDIT_ACTIONS[i]));
    auditObject.addItem("Все объекты");
    for (int i = 1; i < AUDIT_OBJECTS.length; i++)
      auditObject.addItem(objectLabel(AUDIT_OBJECTS[i]));
    var filters = new JPanel(new GridLayout(0, 1));
    filters.add(
        SwingSupport.row(
            SwingSupport.label("С (ГГГГ-ММ-ДД)"),
            auditFrom,
            SwingSupport.label("По"),
            auditUntil,
            auditAction,
            auditObject));
    filters.add(
        SwingSupport.row(
            SwingSupport.label("Описание / пользователь"),
            auditSearch,
            action("Применить", this::filterAudit),
            SwingSupport.label("До 5000 событий; уточняйте фильтры для старых записей")));
    var open =
        action(
            "Открыть событие",
            () -> {
              int row = table.getSelectedRow();
              if (row < 0) return;
              int index = table.convertRowIndexToModel(row);
              var text = new StringBuilder();
              for (int c = 0; c < auditModel.getColumnCount(); c++)
                text.append(auditModel.getColumnName(c))
                    .append(": ")
                    .append(auditModel.getValueAt(index, c))
                    .append('\n');
              SwingSupport.showText(this, "Событие журнала", text.toString());
            });
    root.add(filters, BorderLayout.NORTH);
    root.add(new JScrollPane(table));
    root.add(SwingSupport.row(open), BorderLayout.SOUTH);
    return root;
  }

  private void filterAudit() {
    try {
      var filter =
          new AuditFilter(
              auditFrom.getText().isBlank() ? null : LocalDate.parse(auditFrom.getText().strip()),
              auditUntil.getText().isBlank() ? null : LocalDate.parse(auditUntil.getText().strip()),
              AUDIT_ACTIONS[auditAction.getSelectedIndex()],
              AUDIT_OBJECTS[auditObject.getSelectedIndex()],
              auditSearch.getText());
      controller.execute("Загрузка журнала...", () -> service.audit(filter), this::fillAudit);
    } catch (RuntimeException e) {
      showError(e);
    }
  }

  private void fillAudit(List<AuditEntry> entries) {
    auditModel.setRowCount(0);
    for (var a : entries)
      auditModel.addRow(
          new Object[] {
            time(a.occurredAt()),
            a.actor(),
            actionLabel(a.action()),
            objectLabel(a.objectType()),
            a.objectId(),
            a.result().equals("SUCCESS") ? "Успешно" : "Отказ",
            a.objectType().equals("subject")
                ? LegacyCatalogNames.displayAuditDetails(a.details())
                : a.details()
          });
  }

  private void reload() {
    controller.reload(this::accept);
  }

  private void accept(CatalogController.Snapshot snapshot) {
    Long selected =
        preferredSubject != null
            ? preferredSubject
            : (subject.getSelectedItem() instanceof Subject s ? s.id() : null);
    Long catalog =
        subjectList.getSelectedValue() == null
            ? selected
            : Long.valueOf(subjectList.getSelectedValue().id());
    data = snapshot;
    updating = true;
    subject.removeAllItems();
    subjectListModel.clear();
    for (var s : data.subjects()) {
      subject.addItem(s);
      subjectListModel.addElement(s);
      if (Objects.equals(s.id(), selected)) subject.setSelectedItem(s);
    }
    preferredSubject = null;
    for (int i = 0; i < subjectListModel.size(); i++)
      if (Objects.equals(subjectListModel.get(i).id(), catalog)) subjectList.setSelectedIndex(i);
    if (subjectList.getSelectedIndex() < 0 && !subjectListModel.isEmpty())
      subjectList.setSelectedIndex(0);
    updating = false;
    updateTopicFilter();
    updateCatalogTopics();
    applyFilters();
    ranking.setSubjects(data.subjects());
    fillAudit(data.audit());
    if (!auditFrom.getText().isBlank()
        || !auditUntil.getText().isBlank()
        || !auditSearch.getText().isBlank()
        || auditAction.getSelectedIndex() > 0
        || auditObject.getSelectedIndex() > 0) filterAudit();
  }

  private void updateTopicFilter() {
    updating = true;
    topic.removeAllItems();
    topic.addItem("Все темы");
    var s = (Subject) subject.getSelectedItem();
    if (s != null)
      data.topics().stream().filter(t -> t.subjectId() == s.id()).forEach(topic::addItem);
    updating = false;
  }

  private void updateCatalogTopics() {
    topicListModel.clear();
    var selected = subjectList.getSelectedValue();
    if (selected == null) return;
    data.topics().stream()
        .filter(t -> t.subjectId() == selected.id())
        .forEach(topicListModel::addElement);
  }

  private void applyFilters() {
    if (updating) return;
    var selected = (Subject) subject.getSelectedItem();
    if (selected == null) {
      questionModel.set(List.of());
      count.setText(
          "Нет доступных дисциплин. Администратор создаёт дисциплины и назначения преподавателей.");
      return;
    }
    var bank = new QuestionBank(selected.id());
    data.questions().getOrDefault(selected.id(), List.of()).forEach(bank::add);
    var list =
        bank.search(
            search.getText(),
            topic.getSelectedItem() instanceof Topic t ? t.id() : null,
            type.getSelectedItem() instanceof QuestionType t ? t : null,
            difficulty.getSelectedItem() instanceof Integer i ? i : null,
            archive.getSelectedIndex() == 2 ? null : archive.getSelectedIndex() == 1);
    questionModel.set(list);
    long active = bank.questions().stream().filter(q -> !q.archived()).count();
    count.setText(
        "Показано: "
            + list.size()
            + "    Активных: "
            + active
            + "    В архиве: "
            + (bank.questions().size() - active));
    details.setText("");
  }

  private void resetFilters() {
    search.setText("");
    topic.setSelectedIndex(0);
    type.setSelectedIndex(0);
    difficulty.setSelectedIndex(0);
    archive.setSelectedIndex(0);
  }

  private Question selected() {
    int row = questionsTable.getSelectedRow();
    return row < 0 ? null : questionModel.rows.get(questionsTable.convertRowIndexToModel(row));
  }

  private void editSelected() {
    var q = selected();
    if (q == null) {
      status.setText("Выберите вопрос в таблице.");
      return;
    }
    edit(q);
  }

  private void edit(Question original) {
    if (busy) return;
    var s = (Subject) subject.getSelectedItem();
    if (s == null) {
      JOptionPane.showMessageDialog(
          this, "Сначала создайте дисциплину и тему в разделе справочников.");
      return;
    }
    var topics = data.topics().stream().filter(t -> t.subjectId() == s.id()).toList();
    if (topics.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Создайте хотя бы одну тему для выбранной дисциплины.");
      return;
    }
    var holder = new QuestionDialog[1];
    holder[0] =
        new QuestionDialog(
            this,
            topics,
            original,
            q -> {
              var duplicate =
                  data.questions().getOrDefault(s.id(), List.of()).stream()
                      .filter(
                          existing ->
                              existing.id() != q.id()
                                  && existing.normalizedText().equals(q.normalizedText()))
                      .findFirst();
              if (duplicate.isPresent()) {
                int choice =
                    JOptionPane.showOptionDialog(
                        this,
                        "Совпадение с вопросом №" + duplicate.get().id() + ". Что сделать?",
                        "Совпадение текста",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        new String[] {"Открыть существующий", "Продолжить правку"},
                        "Продолжить правку");
                if (choice == 0) {
                  holder[0].dispose();
                  SwingUtilities.invokeLater(() -> edit(duplicate.get()));
                }
                return;
              }
              holder[0].setSaving(true);
              controller.save(
                  q,
                  saved -> {
                    holder[0].dispose();
                    reload();
                  },
                  failure -> {
                    holder[0].setSaving(false);
                    holder[0].showError(failure.getMessage());
                  });
            });
    holder[0].setVisible(true);
  }

  private void toggleArchive() {
    var q = selected();
    if (q == null) {
      status.setText("Выберите вопрос в таблице.");
      return;
    }
    String verb = q.archived() ? "Восстановить" : "Архивировать";
    if (JOptionPane.showConfirmDialog(
            this,
            verb
                + " вопрос №"
                + q.id()
                + "? История сохранится. Записи не удаляются, используется архив.",
            verb,
            JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) return;
    controller.save(q.archived() ? q.restore() : q.archive(), saved -> reload(), this::showError);
  }

  private void updateDetails() {
    var q = selected();
    if (q == null) return;
    details.setText(
        "Вопрос №"
            + q.id()
            + " · версия "
            + q.revision()
            + " · "
            + (q.archived() ? "Архив" : "Активен")
            + "\n\n"
            + q.text()
            + "\n\nКритерий ответа: "
            + q.guidance());
    details.setCaretPosition(0);
  }

  private void history() {
    var q = selected();
    if (q == null) {
      status.setText("Выберите вопрос в таблице.");
      return;
    }
    controller.history(
        q.id(),
        versions -> {
          var text = new StringBuilder();
          for (var revision : versions) {
            var v = revision.question();
            text.append("Версия ")
                .append(v.revision())
                .append(" · ")
                .append(time(revision.savedAt()))
                .append(v.archived() ? " · архив" : " · активен")
                .append("\nСложность: ")
                .append(v.difficulty())
                .append("; значимость: ")
                .append(v.importance())
                .append("; максимум: ")
                .append(v.maxPoints())
                .append("\n")
                .append(v.text())
                .append("\nКритерий: ")
                .append(v.guidance())
                .append("\n\n");
          }
          SwingSupport.showText(this, "История вопроса №" + q.id(), text.toString());
        });
  }

  private void chooseCsv() {
    var chooser = new JFileChooser();
    chooser.setDialogTitle("Выберите CSV с вопросами");
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("CSV UTF-8 (*.csv)", "csv"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    int duplicatePolicy =
        JOptionPane.showOptionDialog(
            this,
            "Как обработать точные совпадения с банком и внутри CSV? Архив тоже проверяется.",
            "Совпадения при импорте",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            new String[] {"Остановить при совпадении", "Пропускать совпадения", "Отмена"},
            "Остановить при совпадении");
    if (duplicatePolicy < 0 || duplicatePolicy == 2) return;
    controller.preview(
        chooser.getSelectedFile().toPath(),
        duplicatePolicy == 1,
        preview -> {
          var text =
              new StringBuilder(
                  "Готовых вопросов: "
                      + preview.questions().size()
                      + "\nОшибок: "
                      + preview.errors().size()
                      + "\n\n");
          text.append("Пропущено совпадений: ").append(preview.skipped().size()).append('\n');
          preview.skipped().forEach(e -> text.append(e).append('\n'));
          preview.errors().forEach(e -> text.append(e).append('\n'));
          for (int i = 0; i < Math.min(20, preview.questions().size()); i++) {
            var q = preview.questions().get(i);
            text.append(i + 1)
                .append(". ")
                .append(q.type())
                .append(" · ")
                .append(q.text())
                .append('\n');
          }
          if (!preview.canImport()) {
            SwingSupport.showText(this, "Импорт не выполнен: исправьте CSV", text.toString());
            return;
          }
          var area = new JTextArea(text.toString(), 20, 75);
          area.setEditable(false);
          area.setLineWrap(true);
          area.setWrapStyleWord(true);
          if (JOptionPane.showConfirmDialog(
                  this,
                  new JScrollPane(area),
                  "Предпросмотр. Импортировать весь набор?",
                  JOptionPane.OK_CANCEL_OPTION)
              == JOptionPane.OK_OPTION)
            controller.importRows(
                preview.questions(),
                saved -> {
                  JOptionPane.showMessageDialog(this, "Импортировано вопросов: " + saved.size());
                  reload();
                });
        });
  }

  private void subjectEditor(Subject value) {
    var fields =
        names("Дисциплина", value == null ? "" : value.code(), value == null ? "" : value.name());
    if (fields == null) return;
    try {
      var entity = new Subject(value == null ? 0 : value.id(), fields[0], fields[1]);
      controller.subject(
          entity,
          s -> {
            preferredSubject = s.id();
            reload();
          });
    } catch (ValidationException e) {
      showError(e);
    }
  }

  private void topicEditor(Topic value) {
    var s = subjectList.getSelectedValue();
    if (s == null) {
      JOptionPane.showMessageDialog(this, "Выберите дисциплину в списке слева.");
      return;
    }
    var fields =
        names("Тема", value == null ? "" : value.code(), value == null ? "" : value.name());
    if (fields == null) return;
    try {
      controller.topic(
          new Topic(value == null ? 0 : value.id(), s.id(), fields[0], fields[1]), t -> reload());
    } catch (ValidationException e) {
      showError(e);
    }
  }

  private String[] names(String title, String code, String name) {
    var codeField = new JTextField(code, 28);
    var nameField = new JTextField(name, 28);
    var panel = new JPanel(new GridLayout(4, 1, 0, 6));
    panel.add(SwingSupport.label("Уникальный код"));
    panel.add(codeField);
    panel.add(SwingSupport.label("Название"));
    panel.add(nameField);
    while (JOptionPane.showConfirmDialog(
            this, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        new Subject(0, codeField.getText(), nameField.getText());
        return new String[] {codeField.getText(), nameField.getText()};
      } catch (ValidationException e) {
        JOptionPane.showMessageDialog(
            this, e.getMessage(), "Проверьте данные", JOptionPane.WARNING_MESSAGE);
      }
    }
    return null;
  }

  private void bulkEdit() {
    var selected = new ArrayList<Question>();
    for (int row : questionsTable.getSelectedRows())
      selected.add(questionModel.rows.get(questionsTable.convertRowIndexToModel(row)));
    if (selected.isEmpty()) {
      status.setText("Выберите вопросы с Ctrl/Cmd или Shift.");
      return;
    }
    var difficulty = new JComboBox<>(new Object[] {"Не менять", 1, 2, 3, 4, 5});
    var importance = new JComboBox<>(new Object[] {"Не менять", 1, 2, 3, 4, 5});
    var archive = new JComboBox<>(new String[] {"Не менять", "Активные", "Архивные"});
    var fields =
        SwingSupport.form("Сложность", difficulty, "Значимость", importance, "Статус", archive);
    if (JOptionPane.showConfirmDialog(
            this,
            fields,
            "Массовая правка: " + selected.size() + " вопросов",
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) return;
    Integer d = difficulty.getSelectedItem() instanceof Integer v ? v : null,
        i = importance.getSelectedItem() instanceof Integer v ? v : null;
    Boolean a = archive.getSelectedIndex() == 0 ? null : archive.getSelectedIndex() == 2;
    String text =
        "Изменить вопросы "
            + selected.stream().map(Question::id).toList()
            + "?\nСложность: "
            + difficulty.getSelectedItem()
            + "; значимость: "
            + importance.getSelectedItem()
            + "; статус: "
            + archive.getSelectedItem();
    if (JOptionPane.showConfirmDialog(
            this, text, "Подтверждение состава изменений", JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) return;
    controller.execute(
        "Массовое изменение вопросов...",
        () -> service.bulkEdit(selected, d, i, a),
        saved -> reload());
  }

  private void changePassword() {
    var old = new JPasswordField(24);
    var next = new JPasswordField(24);
    var confirm = new JPasswordField(24);
    var form =
        SwingSupport.form(
            "Текущий пароль", old, "Новый пароль (12..256)", next, "Повтор нового пароля", confirm);
    SwingSupport.edit(
        this,
        "Смена пароля",
        form,
        () -> {
          char[] password = next.getPassword(), repeated = confirm.getPassword();
          boolean equal = Arrays.equals(password, repeated);
          Arrays.fill(password, '\0');
          Arrays.fill(repeated, '\0');
          if (!equal) throw new ValidationException("Пароли не совпадают.");
          return true;
        },
        ignored -> {
          char[] before = old.getPassword(), after = next.getPassword();
          controller.execute(
              "Смена пароля...",
              () -> {
                auth.changePassword(before, after);
                return true;
              },
              saved -> JOptionPane.showMessageDialog(this, "Пароль изменён."));
        });
    old.setText("");
    next.setText("");
    confirm.setText("");
  }

  private void logout() {
    controller.execute(
        "Выход из системы...",
        () -> {
          auth.logout();
          return true;
        },
        ignored -> {
          dispose();
          onLogout.run();
        });
  }

  private void setBusy(boolean value) {
    busy = value;
    if (value) disable(getContentPane());
    else {
      enabledBefore.forEach(Component::setEnabled);
      enabledBefore.clear();
    }
    setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
  }

  private void disable(Component component) {
    enabledBefore.putIfAbsent(component, component.isEnabled());
    component.setEnabled(false);
    if (component instanceof Container container)
      for (var child : container.getComponents()) disable(child);
  }

  private void showError(Throwable failure) {
    JOptionPane.showMessageDialog(
        this,
        failure.getMessage() == null ? "Операция не выполнена." : failure.getMessage(),
        "Не удалось выполнить действие",
        JOptionPane.ERROR_MESSAGE);
  }

  private static String time(String value) {
    try {
      return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
          .withZone(ZoneId.systemDefault())
          .format(Instant.parse(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static String objectLabel(String value) {
    return switch (value) {
      case "subject" -> "Дисциплина";
      case "topic" -> "Тема";
      case "question" -> "Вопрос";
      case "user" -> "Пользователь";
      case "group" -> "Группа";
      case "student" -> "Студент";
      case "period" -> "Учебный период";
      case "assignment" -> "Назначение";
      case "ranking_profile" -> "Профиль ранжирования";
      case "backup" -> "Резервная копия";
      default -> value;
    };
  }

  private static String actionLabel(String value) {
    return switch (value) {
      case "SAVE_SUBJECT" -> "Сохранение дисциплины";
      case "SAVE_TOPIC" -> "Сохранение темы";
      case "CREATE_QUESTION" -> "Создание вопроса";
      case "REVISE_QUESTION" -> "Новая версия вопроса";
      case "LOGIN" -> "Вход";
      case "LOGOUT" -> "Выход";
      case "CREATE_ADMIN" -> "Первый администратор";
      case "SAVE_USER" -> "Изменение пользователя";
      case "CHANGE_PASSWORD" -> "Смена пароля";
      case "SAVE_GROUP" -> "Изменение группы";
      case "SAVE_STUDENT" -> "Изменение студента";
      case "SAVE_PERIOD" -> "Изменение периода";
      case "SAVE_ASSIGNMENT" -> "Назначение преподавателя";
      case "SAVE_RANKING_PROFILE" -> "Сохранение профиля";
      case "CALCULATE_RANKING" -> "Расчёт рейтинга";
      case "ADD_OBSERVATION" -> "Добавление наблюдений";
      case "REVIEW_QUESTION" -> "Подтверждение пересмотра";
      case "CREATE_BACKUP" -> "Создание копии";
      case "RESTORE_BACKUP" -> "Восстановление копии";
      default -> value;
    };
  }

  private static DefaultTableModel readOnlyModel(String[] columns) {
    return new DefaultTableModel(columns, 0) {
      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private final class QuestionModel extends AbstractTableModel {
    private final String[] columns = {
      "ID", "Вопрос", "Тема", "Тип", "Сложность", "Расчётная сложность", "Значимость", "Макс. балл", "Статус", "Давность"
    };
    private List<Question> rows = List.of();

    void set(List<Question> rows) {
      this.rows = List.copyOf(rows);
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return columns.length;
    }

    @Override
    public String getColumnName(int column) {
      return columns[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return switch (column) {
        case 0 -> Long.class;
        case 4, 5, 6 -> Integer.class;
        case 7 -> java.math.BigDecimal.class;
        default -> String.class;
      };
    }

    @Override
    public Object getValueAt(int row, int column) {
      var q = rows.get(row);
      var rank = data.rankings().get(q.id());
      return switch (column) {
        case 0 -> q.id();
        case 1 -> q.text().replace('\n', ' ');
        case 2 ->
            data.topics().stream()
                .filter(t -> t.id() == q.topicId())
                .map(Topic::name)
                .findFirst()
                .orElse("Тема удалена");
        case 3 -> q.type().toString();
        case 4 -> q.difficulty();
        case 5 -> rank == null ? null : rank.difficulty();
        case 6 -> q.importance();
        case 7 -> q.maxPoints();
        case 8 -> q.archived() ? "Архив" : "Активен";
        case 9 -> rank != null && rank.old() ? "!" : "";
        default -> "";
      };
    }
  }
}
