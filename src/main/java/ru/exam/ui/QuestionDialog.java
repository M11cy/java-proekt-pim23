package ru.exam.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import ru.exam.domain.*;
import ru.exam.service.CsvQuestionImporter;

final class QuestionDialog extends JDialog {
  private final JComboBox<Topic> topic;
  private final JComboBox<QuestionType> type = new JComboBox<>(QuestionType.values());
  private final JSpinner difficulty = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
  private final JSpinner importance = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
  private final JTextField points = new JTextField("10", 10);
  private final JTextArea text = new JTextArea(6, 45);
  private final JTextArea guidance = new JTextArea(4, 45);
  private final JLabel error = SwingSupport.label(" ");
  private final JButton save;
  private final JButton cancel;
  private final Question original;
  private final String initialState;
  private boolean saving;

  QuestionDialog(JFrame owner, List<Topic> topics, Question original, Consumer<Question> onSave) {
    super(
        owner,
        original == null ? "Новый вопрос" : "Редактирование вопроса №" + original.id(),
        true);
    this.original = original;
    topic = new JComboBox<>(topics.toArray(Topic[]::new));
    topic.setRenderer(SwingSupport.literalListRenderer());
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    guidance.setLineWrap(true);
    guidance.setWrapStyleWord(true);
    if (original != null) {
      topics.stream()
          .filter(t -> t.id() == original.topicId())
          .findFirst()
          .ifPresent(topic::setSelectedItem);
      type.setSelectedItem(original.type());
      difficulty.setValue(original.difficulty());
      importance.setValue(original.importance());
      points.setText(original.maxPoints().toPlainString());
      text.setText(original.text());
      guidance.setText(original.guidance());
    }
    var form = new JPanel(new GridBagLayout());
    var constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.anchor = GridBagConstraints.NORTHWEST;
    constraints.insets = new Insets(5, 5, 5, 5);
    addField(form, constraints, "Тема", topic);
    addField(form, constraints, "Тип задания", type);
    addField(form, constraints, "Сложность от 1 до 5", difficulty);
    addField(form, constraints, "Значимость от 1 до 5", importance);
    addField(form, constraints, "Максимальный балл", points);
    addField(form, constraints, "Текст вопроса", new JScrollPane(text));
    addField(form, constraints, "Критерий ответа", new JScrollPane(guidance));
    save =
        SwingSupport.button("Сохранить",
            () -> {
              try {
                onSave.accept(read());
              } catch (ValidationException e) {
                showError(e.getMessage());
              }
            });
    cancel = SwingSupport.button("Отмена", this::requestClose);
    var bottom = new JPanel(new BorderLayout());
    bottom.add(error, BorderLayout.NORTH);
    bottom.add(SwingSupport.row(save, cancel), BorderLayout.SOUTH);
    add(new JScrollPane(form), BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(save);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            requestClose();
          }
        });
    getRootPane()
        .registerKeyboardAction(
            e -> requestClose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    initialState = state();
    setMinimumSize(new Dimension(640, 560));
    setSize(760, 710);
    setLocationRelativeTo(owner);
  }

  private void addField(JPanel form, GridBagConstraints c, String title, Component field) {
    c.gridx = 0;
    c.weightx = 0;
    c.fill = GridBagConstraints.NONE;
    var label = SwingSupport.label(title);
    label.setLabelFor(field);
    form.add(label, c);
    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    form.add(field, c);
    c.gridy++;
  }

  private Question read() {
    if (topic.getSelectedItem() == null)
      throw new ValidationException("Сначала создайте тему дисциплины.");
    try {
      difficulty.commitEdit();
      importance.commitEdit();
    } catch (java.text.ParseException e) {
      throw new ValidationException("Сложность и значимость должны быть целыми числами от 1 до 5.");
    }
    return new Question(
        original == null ? 0 : original.id(),
        ((Topic) topic.getSelectedItem()).id(),
        text.getText(),
        (QuestionType) type.getSelectedItem(),
        (Integer) difficulty.getValue(),
        (Integer) importance.getValue(),
        CsvQuestionImporter.parsePoints(points.getText()),
        guidance.getText(),
        original != null && original.archived(),
        original == null ? 0 : original.revision());
  }

  void showError(String message) {
    error.setText(message);
    text.requestFocusInWindow();
  }

  void setSaving(boolean saving) {
    this.saving = saving;
    save.setEnabled(!saving);
    cancel.setEnabled(!saving);
  }

  private String state() {
    return topic.getSelectedItem()
        + "|"
        + type.getSelectedItem()
        + "|"
        + difficulty.getValue()
        + "|"
        + importance.getValue()
        + "|"
        + points.getText()
        + "|"
        + text.getText()
        + "|"
        + guidance.getText();
  }

  private void requestClose() {
    if (saving) return;
    if (!state().equals(initialState)
        && JOptionPane.showConfirmDialog(
                this,
                "Закрыть форму без сохранения изменений?",
                "Несохранённые изменения",
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    dispose();
  }
}
