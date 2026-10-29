package ru.exam.ui;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.SwingWorker;
import ru.exam.domain.*;
import ru.exam.service.*;

/** Запускает сценарии в фоне, возвращает результаты исключительно в EDT. */
public final class CatalogController {
  public record Snapshot(
      List<Subject> subjects,
      List<Topic> topics,
      Map<Long, List<Question>> questions,
      List<AuditEntry> audit) {}

  private final CatalogService service;
  private final Consumer<Boolean> busy;
  private final Consumer<String> status;
  private final Consumer<Throwable> error;
  private boolean running;

  public CatalogController(
      CatalogService service,
      Consumer<Boolean> busy,
      Consumer<String> status,
      Consumer<Throwable> error) {
    this.service = service;
    this.busy = busy;
    this.status = status;
    this.error = error;
  }

  private <T> void run(
      String label, Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
    if (running) return;
    running = true;
    busy.accept(true);
    status.accept(label);
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.call();
      }

      @Override
      protected void done() {
        running = false;
        busy.accept(false);
        try {
          T result = get();
          status.accept("Готово");
          success.accept(result);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          failure.accept(e);
        } catch (ExecutionException e) {
          status.accept("Операция не выполнена");
          failure.accept(e.getCause());
        }
      }
    }.execute();
  }

  public void reload(Consumer<Snapshot> success) {
    run(
        "Загрузка банка вопросов...",
        () -> {
          var subjects = service.subjects();
          var topics = new ArrayList<Topic>();
          var questions = new HashMap<Long, List<Question>>();
          for (var s : subjects) {
            topics.addAll(service.topics(s.id()));
            questions.put(s.id(), service.questions(s.id()));
          }
          return new Snapshot(
              subjects, List.copyOf(topics), Map.copyOf(questions), service.audit());
        },
        success,
        error);
  }

  public void save(Question q, Consumer<Question> success, Consumer<Throwable> failure) {
    run("Сохранение вопроса...", () -> service.save(q), success, failure);
  }

  public void subject(Subject value, Consumer<Subject> success) {
    run(
        "Сохранение дисциплины...",
        () ->
            value.id() == 0
                ? service.createSubject(value.code(), value.name())
                : service.renameSubject(value.id(), value.code(), value.name()),
        success,
        error);
  }

  public void topic(Topic value, Consumer<Topic> success) {
    run(
        "Сохранение темы...",
        () ->
            value.id() == 0
                ? service.createTopic(value.subjectId(), value.code(), value.name())
                : service.renameTopic(value.id(), value.subjectId(), value.code(), value.name()),
        success,
        error);
  }

  public void preview(Path path, Consumer<ImportPreview> success) {
    run("Проверка CSV...", () -> new CsvQuestionImporter(service).preview(path), success, error);
  }

  public void importRows(List<Question> rows, Consumer<List<Question>> success) {
    run("Импорт вопросов...", () -> service.importQuestions(rows), success, error);
  }

  public void history(long id, Consumer<List<Revision>> success) {
    run("Загрузка версий...", () -> service.history(id), success, error);
  }

}
