package ru.exam.ui;

import static ru.exam.domain.TicketSet.*;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import ru.exam.domain.*;

final class TicketSettings {
  private final JTextField name = new JTextField("Экзаменационные билеты", 24);
  private final JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));
  private final JSpinner size = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
  private final JSpinner repeat = new JSpinner(new SpinnerNumberModel(2, 1, 100, 1));
  private final JTextField target = new JTextField("3");
  private final JCheckBox hard = new JCheckBox("Ограничить отклонение");
  private final JTextField tolerance = new JTextField("0,5");
  private final JTextField seed =
      new JTextField(Long.toString(ThreadLocalRandom.current().nextLong()));
  private final JTextField budget = new JTextField("25000");
  private final JTextField weights = new JTextField("40 25 15 15 5");
  private final JComboBox<String> topicMode =
      new JComboBox<>(new String[] {"Без квот по темам", "Квоты на билет", "Квоты на комплект"});
  private final JCheckBox exactTypes = new JCheckBox("Точные квоты типов на билет");
  private final JPanel slots = new JPanel(new GridLayout(0, 5, 5, 5));
  private final List<Position> positions = new ArrayList<>();
  private final List<Item> bank;
  private final List<Topic> topics;
  private final DefaultTableModel topicModel = quotaModel("Тема");
  private final DefaultTableModel typeModel = quotaModel("Тип");
  private final DefaultTableModel questionModel =
      new DefaultTableModel(new String[] {"Вопрос", "Тема", "Тип", "Правило"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return column == 3;
        }
      };
  private final JTable topicTable = new JTable(topicModel);
  private final JTable typeTable = new JTable(typeModel);
  private final JTable questionTable = new JTable(questionModel);

  TicketSettings(List<Item> bank, List<Topic> topics) {
    this.bank = List.copyOf(bank);
    this.topics = List.copyOf(topics);
    topics.forEach(t -> topicModel.addRow(new Object[] {t.name(), true, 0}));
    for (var type : QuestionType.values())
      typeModel.addRow(new Object[] {type.toString(), true, 0});
    bank.forEach(
        q -> questionModel.addRow(new Object[] {q.text(), q.topic(), q.type(), "Обычный"}));
    questionTable.getColumnModel().getColumn(0).setPreferredWidth(380);
    questionTable
        .getColumnModel()
        .getColumn(3)
        .setCellEditor(
            new DefaultCellEditor(
                new JComboBox<>(new String[] {"Обычный", "Обязательный", "Исключить"})));
    for (var table : List.of(topicTable, typeTable, questionTable)) SwingSupport.table(table);
    size.addChangeListener(e -> showSlots());
    showSlots();
  }

  void open(Component owner, Consumer<Config> generate) {
    var tabs = new JTabbedPane();
    tabs.addTab(
        "Параметры",
        SwingSupport.form(
            "Название комплекта",
            name,
            "Билетов",
            count,
            "Вопросов в билете",
            size,
            "Максимум включений одного вопроса",
            repeat,
            "Целевая сложность",
            target,
            "Средняя сложность билета",
            hard,
            "Допуск",
            tolerance,
            "Начальное число поиска (seed)",
            seed,
            "Лимит поиска",
            budget,
            "Веса: разброс, цель, повторы, темы, рейтинг",
            weights));
    tabs.addTab("Позиции", new JScrollPane(slots));
    var quota = new JPanel(new GridLayout(2, 1, 5, 5));
    quota.add(quotaPanel(topicMode, topicTable));
    quota.add(quotaPanel(exactTypes, typeTable));
    tabs.addTab("Темы, типы и квоты", quota);
    tabs.addTab("Обязательные и исключённые", new JScrollPane(questionTable));
    var body = new JPanel(new BorderLayout());
    body.add(tabs);
    body.setPreferredSize(new Dimension(770, 420));
    SwingSupport.edit(owner, "Настройки комплекта", body, this::read, generate);
  }

  String title() {
    return Organization.text(name.getText(), "Название комплекта", 120);
  }

  private Config read() {
    title();
    for (var table : List.of(topicTable, typeTable, questionTable))
      if (table.isEditing() && !table.getCellEditor().stopCellEditing())
        throw new ValidationException("Проверьте значение в таблице настроек.");
    try {
      count.commitEdit();
      size.commitEdit();
      repeat.commitEdit();
      int length = (Integer) size.getValue();
      var rules = new ArrayList<Slot>();
      for (int i = 0; i < length; i++) rules.add(positions.get(i).read());
      var allowedTopics = new HashSet<Long>();
      var topicQuotas = new HashMap<Long, Integer>();
      for (int i = 0; i < topics.size(); i++) {
        long id = topics.get(i).id();
        if ((Boolean) topicModel.getValueAt(i, 1)) allowedTopics.add(id);
        if (topicMode.getSelectedIndex() > 0)
          topicQuotas.put(
              id, allowedTopics.contains(id) ? (Integer) topicModel.getValueAt(i, 2) : 0);
      }
      var allowedTypes = EnumSet.noneOf(QuestionType.class);
      var typeQuotas = new EnumMap<QuestionType, Integer>(QuestionType.class);
      for (var type : QuestionType.values()) {
        if ((Boolean) typeModel.getValueAt(type.ordinal(), 1)) allowedTypes.add(type);
        if (exactTypes.isSelected())
          typeQuotas.put(
              type,
              allowedTypes.contains(type) ? (Integer) typeModel.getValueAt(type.ordinal(), 2) : 0);
      }
      var required = new HashSet<Long>();
      var excluded = new HashSet<Long>();
      for (int i = 0; i < bank.size(); i++) {
        var q = bank.get(i);
        var rule = questionModel.getValueAt(i, 3);
        if ("Обязательный".equals(rule)) required.add(q.id());
        if ("Исключить".equals(rule)
            || !allowedTopics.contains(q.topicId())
            || !allowedTypes.contains(q.type())) excluded.add(q.id());
      }
      var values =
          Arrays.stream(weights.getText().strip().split("\\s+"))
              .map(TicketSettings::decimal)
              .toList();
      return new Config(
          (Integer) count.getValue(),
          rules,
          (Integer) repeat.getValue(),
          decimal(target.getText()),
          hard.isSelected() ? decimal(tolerance.getText()) : null,
          Long.parseLong(seed.getText().strip()),
          Integer.parseInt(budget.getText().strip()),
          required,
          excluded,
          topicQuotas,
          topicMode.getSelectedIndex() == 2,
          typeQuotas,
          values);
    } catch (java.text.ParseException | NumberFormatException e) {
      throw new ValidationException(
          "Проверьте числовые параметры. Пять весов вводятся через пробел.");
    }
  }

  private static double decimal(String text) {
    return Double.parseDouble(text.strip().replace(',', '.'));
  }

  private void showSlots() {
    int length = (Integer) size.getValue();
    while (positions.size() < length) positions.add(new Position(topics));
    slots.removeAll();
    for (String name : List.of("Позиция", "Тема", "Тип", "От сложности", "До сложности"))
      slots.add(SwingSupport.label(name));
    for (int i = 0; i < length; i++) {
      var p = positions.get(i);
      slots.add(SwingSupport.label(Integer.toString(i + 1)));
      slots.add(p.topic);
      slots.add(p.type);
      slots.add(p.min);
      slots.add(p.max);
    }
    slots.revalidate();
    slots.repaint();
  }

  private static JPanel quotaPanel(Component option, JTable table) {
    var panel = new JPanel(new BorderLayout());
    panel.add(option, BorderLayout.NORTH);
    panel.add(new JScrollPane(table));
    return panel;
  }

  private static DefaultTableModel quotaModel(String name) {
    return new DefaultTableModel(new String[] {name, "Разрешено", "Квота (целое число)"}, 0) {
      @Override
      public boolean isCellEditable(int row, int col) {
        return col > 0;
      }

      @Override
      public Class<?> getColumnClass(int col) {
        return switch (col) {
          case 1 -> Boolean.class;
          case 2 -> Integer.class;
          default -> String.class;
        };
      }
    };
  }

  private static final class Position {
    private final JComboBox<Object> topic = new JComboBox<>();
    private final JComboBox<Object> type = new JComboBox<>();
    private final JComboBox<Integer> min = new JComboBox<>(new Integer[] {1, 2, 3, 4, 5});
    private final JComboBox<Integer> max = new JComboBox<>(new Integer[] {1, 2, 3, 4, 5});

    Position(List<Topic> topics) {
      topic.addItem("Любая");
      topics.forEach(topic::addItem);
      type.addItem("Любой");
      for (var value : QuestionType.values()) type.addItem(value);
      max.setSelectedItem(5);
      topic.setRenderer(SwingSupport.literalListRenderer());
    }

    Slot read() {
      return new Slot(
          topic.getSelectedItem() instanceof Topic t ? t.id() : null,
          type.getSelectedItem() instanceof QuestionType t ? t : null,
          (Integer) min.getSelectedItem(),
          (Integer) max.getSelectedItem());
    }
  }
}
