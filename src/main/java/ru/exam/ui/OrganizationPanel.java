package ru.exam.ui;

import java.awt.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import ru.exam.domain.*;
import ru.exam.domain.Organization.*;
import ru.exam.service.*;

final class OrganizationPanel extends JPanel {
  private record Data(
      OrganizationService.Snapshot organization, List<User> users, List<Subject> subjects) {}

  private final OrganizationService service;
  private final AuthService auth;
  private final CatalogService catalog;
  private final CatalogController controller;
  private final boolean admin;
  private OrganizationService.Snapshot data =
      new OrganizationService.Snapshot(List.of(), List.of(), List.of(), List.of());
  private List<User> users = List.of();
  private List<Subject> subjects = List.of();
  private final Records<User> userRows;
  private final Records<Group> groupRows;
  private final Records<Student> studentRows;
  private final Records<Period> periodRows;
  private final Records<Assignment> assignmentRows;

  OrganizationPanel(
      OrganizationService service,
      AuthService auth,
      CatalogService catalog,
      CatalogController controller,
      boolean admin) {
    super(new BorderLayout());
    this.service = service;
    this.auth = auth;
    this.catalog = catalog;
    this.controller = controller;
    this.admin = admin;
    userRows =
        new Records<>(
            new String[] {"ID", "Логин", "ФИО", "Роль", "Активен"},
            u -> new Object[] {u.id(), u.login(), u.name(), u.role(), u.active()},
            User::active,
            this::editUser,
            admin);
    groupRows =
        new Records<>(
            new String[] {"ID", "Код", "Группа", "Активна"},
            g -> new Object[] {g.id(), g.code(), g.name(), g.active()},
            Group::active,
            this::editGroup,
            admin);
    studentRows =
        new Records<>(
            new String[] {"ID", "Номер", "ФИО", "Группа", "Активен"},
            s -> new Object[] {s.id(), s.number(), s.name(), groupName(s.groupId()), s.active()},
            Student::active,
            this::editStudent,
            admin);
    periodRows =
        new Records<>(
            new String[] {"ID", "Период", "Начало", "Окончание", "Активен"},
            p -> new Object[] {p.id(), p.name(), p.startsOn(), p.endsOn(), p.active()},
            Period::active,
            this::editPeriod,
            admin);
    assignmentRows =
        new Records<>(
            new String[] {"ID", "Преподаватель", "Дисциплина", "Группа", "Период", "Активно"},
            a ->
                new Object[] {
                  a.id(),
                  userName(a.teacherId()),
                  subjectName(a.subjectId()),
                  groupName(a.groupId()),
                  periodName(a.periodId()),
                  a.active()
                },
            Assignment::active,
            this::editAssignment,
            admin);
    var tabs = new JTabbedPane();
    if (admin) tabs.addTab("Пользователи", userRows);
    tabs.addTab("Группы", groupRows);
    tabs.addTab("Студенты", studentRows);
    tabs.addTab("Учебные периоды", periodRows);
    tabs.addTab("Назначения", assignmentRows);
    add(SwingSupport.row(SwingSupport.button("Обновить", this::refresh)), BorderLayout.NORTH);
    add(tabs);
  }

  void refresh() {
    controller.execute(
        "Загрузка учебной организации...",
        () -> new Data(service.snapshot(), admin ? auth.users() : List.of(), catalog.subjects()),
        value -> {
          data = value.organization();
          users = value.users();
          subjects = value.subjects();
          userRows.setRows(users);
          groupRows.setRows(data.groups());
          studentRows.setRows(data.students());
          periodRows.setRows(data.periods());
          assignmentRows.setRows(data.assignments());
        });
  }

  private String groupName(long id) {
    return data.groups().stream()
        .filter(g -> g.id() == id)
        .map(Group::code)
        .findFirst()
        .orElse("#" + id);
  }

  private String userName(long id) {
    return users.stream().filter(u -> u.id() == id).map(User::name).findFirst().orElse("#" + id);
  }

  private String subjectName(long id) {
    return subjects.stream()
        .filter(s -> s.id() == id)
        .map(Subject::name)
        .findFirst()
        .orElse("#" + id);
  }

  private String periodName(long id) {
    return data.periods().stream()
        .filter(p -> p.id() == id)
        .map(Period::name)
        .findFirst()
        .orElse("#" + id);
  }

  private void editUser(User value) {
    var login = new JTextField(value == null ? "" : value.login(), 24);
    login.setEditable(value == null);
    var name = new JTextField(value == null ? "" : value.name(), 24);
    var role = new JComboBox<>(User.Role.values());
    role.setSelectedItem(value == null ? User.Role.TEACHER : value.role());
    var active = new JCheckBox("Активен", value == null || value.active());
    var password = new JPasswordField(24);
    var fields =
        SwingSupport.form(
            "Логин",
            login,
            "ФИО",
            name,
            "Роль",
            role,
            "Состояние",
            active,
            value == null ? "Пароль (12..256)" : "Новый пароль (пусто: сохранить)",
            password);
    SwingSupport.edit(
        this,
        value == null ? "Новый пользователь" : "Изменение пользователя",
        fields,
        () -> {
          Organization.text(name.getText(), "ФИО", 200);
          return name.getText();
        },
        ignored -> {
          char[] secret = password.getPassword();
          if (value != null && secret.length == 0) secret = null;
          char[] next = secret;
          String username = login.getText(), display = name.getText();
          User.Role selected = (User.Role) role.getSelectedItem();
          boolean enabled = active.isSelected();
          controller.execute(
              "Сохранение пользователя...",
              () ->
                  auth.saveUser(
                      value == null ? 0 : value.id(), username, display, selected, enabled, next),
              saved -> refresh());
        });
    password.setText("");
  }

  private void editGroup(Group value) {
    var code = new JTextField(value == null ? "" : value.code(), 24);
    var name = new JTextField(value == null ? "" : value.name(), 24);
    var active = new JCheckBox("Активна", value == null || value.active());
    SwingSupport.edit(
        this,
        "Группа",
        SwingSupport.form("Код", code, "Название", name, "Состояние", active),
        () ->
            new Group(
                value == null ? 0 : value.id(),
                code.getText(),
                name.getText(),
                active.isSelected()),
        g ->
            controller.execute(
                "Сохранение группы...", () -> service.saveGroup(g), saved -> refresh()));
  }

  private void editStudent(Student value) {
    var number = new JTextField(value == null ? "" : value.number(), 24);
    var name = new JTextField(value == null ? "" : value.name(), 24);
    var groups = combo(data.groups());
    if (value != null)
      data.groups().stream()
          .filter(g -> g.id() == value.groupId())
          .findFirst()
          .ifPresent(groups::setSelectedItem);
    var active = new JCheckBox("Активен", value == null || value.active());
    SwingSupport.edit(
        this,
        "Студент",
        SwingSupport.form(
            "Учётный номер", number, "ФИО", name, "Группа", groups, "Состояние", active),
        () ->
            new Student(
                value == null ? 0 : value.id(),
                required(groups).id(),
                number.getText(),
                name.getText(),
                active.isSelected()),
        s ->
            controller.execute(
                "Сохранение студента...", () -> service.saveStudent(s), saved -> refresh()));
  }

  private void editPeriod(Period value) {
    var name = new JTextField(value == null ? "" : value.name(), 24);
    var start =
        new JTextField(
            value == null ? LocalDate.now().toString() : value.startsOn().toString(), 12);
    var end =
        new JTextField(
            value == null ? LocalDate.now().plusMonths(4).toString() : value.endsOn().toString(),
            12);
    var active = new JCheckBox("Активен", value == null || value.active());
    SwingSupport.edit(
        this,
        "Учебный период",
        SwingSupport.form(
            "Название",
            name,
            "Начало (ГГГГ-ММ-ДД)",
            start,
            "Окончание (ГГГГ-ММ-ДД)",
            end,
            "Состояние",
            active),
        () ->
            new Period(
                value == null ? 0 : value.id(),
                name.getText(),
                LocalDate.parse(start.getText().strip()),
                LocalDate.parse(end.getText().strip()),
                active.isSelected()),
        p ->
            controller.execute(
                "Сохранение периода...", () -> service.savePeriod(p), saved -> refresh()));
  }

  private void editAssignment(Assignment value) {
    var teachers =
        combo(
            users.stream()
                .filter(
                    u ->
                        u.role() == User.Role.TEACHER
                            || (value != null && u.id() == value.teacherId()))
                .toList());
    var disciplines = combo(subjects);
    var groups = combo(data.groups());
    var periods = combo(data.periods());
    if (value != null) {
      users.stream()
          .filter(u -> u.id() == value.teacherId())
          .findFirst()
          .ifPresent(teachers::setSelectedItem);
      subjects.stream()
          .filter(s -> s.id() == value.subjectId())
          .findFirst()
          .ifPresent(disciplines::setSelectedItem);
      data.groups().stream()
          .filter(g -> g.id() == value.groupId())
          .findFirst()
          .ifPresent(groups::setSelectedItem);
      data.periods().stream()
          .filter(p -> p.id() == value.periodId())
          .findFirst()
          .ifPresent(periods::setSelectedItem);
    }
    var active = new JCheckBox("Активно", value == null || value.active());
    SwingSupport.edit(
        this,
        "Назначение преподавателя",
        SwingSupport.form(
            "Преподаватель",
            teachers,
            "Дисциплина",
            disciplines,
            "Группа",
            groups,
            "Период",
            periods,
            "Состояние",
            active),
        () ->
            new Assignment(
                value == null ? 0 : value.id(),
                required(teachers).id(),
                required(disciplines).id(),
                required(groups).id(),
                required(periods).id(),
                active.isSelected()),
        a ->
            controller.execute(
                "Сохранение назначения...", () -> service.saveAssignment(a), saved -> refresh()));
  }

  private static <T> JComboBox<T> combo(List<T> values) {
    var combo = new JComboBox<T>();
    values.forEach(combo::addItem);
    combo.setRenderer(SwingSupport.literalListRenderer());
    return combo;
  }

  @SuppressWarnings("unchecked")
  private static <T> T required(JComboBox<T> combo) {
    if (combo.getSelectedItem() == null)
      throw new ValidationException("Заполните связанные справочники.");
    return (T) combo.getSelectedItem();
  }

  private static final class Records<T> extends JPanel {
    private List<T> all = List.of(), shown = List.of();
    private final Function<T, Object[]> columns;
    private final Predicate<T> active;
    private final JTextField search = new JTextField(22);
    private final JCheckBox archive = new JCheckBox("Показывать архив");
    private final DefaultTableModel model;
    private final JTable table;

    Records(
        String[] headers,
        Function<T, Object[]> columns,
        Predicate<T> active,
        Consumer<T> edit,
        boolean editable) {
      super(new BorderLayout());
      this.columns = columns;
      this.active = active;
      model =
          new DefaultTableModel(headers, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
              return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
              return getRowCount() > 0 && getValueAt(0, c) != null
                  ? getValueAt(0, c).getClass()
                  : Object.class;
            }
          };
      table = new JTable(model);
      SwingSupport.table(table);
      var toolbar = SwingSupport.row(SwingSupport.label("Поиск"), search, archive);
      if (editable) {
        toolbar.add(SwingSupport.button("Добавить", () -> edit.accept(null)));
        toolbar.add(
            SwingSupport.button(
                "Изменить / архивировать",
                () -> {
                  int row = table.getSelectedRow();
                  if (row >= 0) edit.accept(shown.get(table.convertRowIndexToModel(row)));
                }));
      }
      search
          .getDocument()
          .addDocumentListener(
              new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                  filter();
                }

                public void removeUpdate(DocumentEvent e) {
                  filter();
                }

                public void changedUpdate(DocumentEvent e) {
                  filter();
                }
              });
      archive.addActionListener(e -> filter());
      add(toolbar, BorderLayout.NORTH);
      add(new JScrollPane(table));
    }

    void setRows(List<T> rows) {
      all = List.copyOf(rows);
      filter();
    }

    private void filter() {
      String needle = search.getText().strip().toLowerCase(Locale.ROOT);
      shown =
          all.stream()
              .filter(r -> archive.isSelected() || active.test(r))
              .filter(
                  r -> Arrays.toString(columns.apply(r)).toLowerCase(Locale.ROOT).contains(needle))
              .toList();
      model.setRowCount(0);
      shown.forEach(r -> model.addRow(columns.apply(r)));
    }
  }
}
