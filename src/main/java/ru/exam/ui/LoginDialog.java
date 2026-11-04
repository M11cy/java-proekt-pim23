package ru.exam.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import ru.exam.service.AuthService;

public final class LoginDialog extends JDialog {
  private boolean working;

  public LoginDialog(AuthService auth, boolean setup, Runnable success) {
    super((Frame) null, setup ? "Создание администратора" : "Вход в систему", true);
    var login = new JTextField(24);
    var name = new JTextField(24);
    var password = new JPasswordField(24);
    var confirm = new JPasswordField(24);
    var error = SwingSupport.label(" ");
    var submit = new JButton(setup ? "Создать администратора" : "Войти");
    var cancel =
        SwingSupport.button(
            "Отмена",
            () -> {
              if (!working) dispose();
            });
    var fields = new JPanel(new GridLayout(0, 2, 8, 8));
    fields.add(SwingSupport.label("Логин"));
    fields.add(login);
    if (setup) {
      fields.add(SwingSupport.label("ФИО"));
      fields.add(name);
    }
    fields.add(SwingSupport.label(setup ? "Пароль (12..256 символов)" : "Пароль"));
    fields.add(password);
    if (setup) {
      fields.add(SwingSupport.label("Повтор пароля"));
      fields.add(confirm);
    }
    var root = new JPanel(new BorderLayout(8, 8));
    root.add(fields, BorderLayout.CENTER);
    var bottom = new JPanel(new BorderLayout());
    bottom.add(error, BorderLayout.NORTH);
    bottom.add(SwingSupport.row(submit, cancel), BorderLayout.SOUTH);
    root.add(bottom, BorderLayout.SOUTH);
    add(root);
    submit.addActionListener(
        e -> {
          char[] secret = password.getPassword(), repeated = confirm.getPassword();
          if (setup && !Arrays.equals(secret, repeated)) {
            Arrays.fill(secret, '\0');
            Arrays.fill(repeated, '\0');
            error.setText("Пароли не совпадают.");
            return;
          }
          Arrays.fill(repeated, '\0');
          String username = login.getText(), display = name.getText();
          working = true;
          submit.setEnabled(false);
          cancel.setEnabled(false);
          login.setEnabled(false);
          name.setEnabled(false);
          password.setEnabled(false);
          confirm.setEnabled(false);
          error.setText("Проверка...");
          new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
              try {
                if (setup) auth.bootstrap(username, display, secret);
                else auth.login(username, secret);
                return null;
              } finally {
                Arrays.fill(secret, '\0');
              }
            }

            @Override
            protected void done() {
              working = false;
              submit.setEnabled(true);
              cancel.setEnabled(true);
              login.setEnabled(true);
              name.setEnabled(true);
              password.setEnabled(true);
              confirm.setEnabled(true);
              password.setText("");
              confirm.setText("");
              try {
                get();
                dispose();
                success.run();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                error.setText("Вход прерван.");
              } catch (ExecutionException ex) {
                error.setText(ex.getCause().getMessage());
                pack();
              }
            }
          }.execute();
        });
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (!working) dispose();
          }
        });
    getRootPane().setDefaultButton(submit);
    pack();
    setMinimumSize(new Dimension(500, getHeight()));
    setLocationRelativeTo(null);
  }
}
