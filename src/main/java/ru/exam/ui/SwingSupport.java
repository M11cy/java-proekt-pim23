package ru.exam.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;

/** Вспомогательные операции стандартных компонентов Swing. */
final class SwingSupport {
  private SwingSupport() {}

  static JPanel row(Component... children) {
    var panel = new JPanel(new FlowLayout(FlowLayout.LEADING));
    for (var child : children) panel.add(child);
    return panel;
  }

  static JLabel label(String text) {
    var label = new JLabel();
    label.putClientProperty("html.disable", Boolean.TRUE);
    label.setText(text);
    return label;
  }

  static JButton button(String text, Runnable action) {
    var button = new JButton(text);
    button.addActionListener(event -> action.run());
    return button;
  }

  static void table(JTable table) {
    table.setAutoCreateRowSorter(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getTableHeader().setReorderingAllowed(false);
    table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable table, Object value,
          boolean selected, boolean focused, int row, int column) {
        putClientProperty("html.disable", Boolean.TRUE);
        return super.getTableCellRendererComponent(table, value, selected, focused, row, column);
      }
    });
  }

  static DefaultListCellRenderer literalListRenderer() {
    return new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> list, Object value, int index,
          boolean selected, boolean focused) {
        putClientProperty("html.disable", Boolean.TRUE);
        return super.getListCellRendererComponent(list, value, index, selected, focused);
      }
    };
  }

  static void showText(Component owner, String title, String text) {
    var area = new JTextArea(text, 24, 85);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setCaretPosition(0);
    JOptionPane.showMessageDialog(owner, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
  }
}
