package ru.exam.service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;
import ru.exam.domain.*;

/** Предпросмотр не изменяет БД; окончательное сохранение повторно проверяет весь набор. */
public final class CsvQuestionImporter {
  private static final List<String> HEADER =
      List.of(
          "subject_code",
          "topic_code",
          "type",
          "text",
          "difficulty",
          "importance",
          "max_points",
          "answer_guidance");
  private final CatalogService service;

  public CsvQuestionImporter(CatalogService service) {
    this.service = service;
  }

  public ImportPreview preview(Path path) {
    var questions = new ArrayList<Question>();
    var errors = new ArrayList<String>();
    try {
      if (Files.size(path) > 10 * 1024 * 1024) return failure("Размер CSV превышает 10 МБ.");
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (content.startsWith("\ufeff")) content = content.substring(1);
      var subjects = new HashMap<String, Subject>();
      var topics = new HashMap<String, Topic>();
      var banks = new HashMap<Long, QuestionBank>();
      for (var subject : service.subjects()) {
        subjects.put(subject.code(), subject);
        for (var topic : service.topics(subject.id()))
          topics.put(subject.code() + "\n" + topic.code(), topic);
        var bank = new QuestionBank(subject.id());
        service.questions(subject.id()).forEach(bank::add);
        banks.put(bank.subjectId(), bank);
      }
      CSVFormat format =
          CSVFormat.DEFAULT
              .builder()
              .setDelimiter(';')
              .setHeader()
              .setSkipHeaderRecord(true)
              .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
              .get();
      try (var parser = CSVParser.parse(content, format)) {
        if (!parser.getHeaderNames().equals(HEADER))
          return failure("Неверный заголовок. Нужны столбцы: " + String.join(";", HEADER));
        for (CSVRecord row : parser) {
          long number = row.getRecordNumber() + 1;
          if (row.getRecordNumber() > 10_000) {
            errors.add("В CSV более 10 000 записей.");
            break;
          }
          try {
            if (row.size() != HEADER.size()) throw new ValidationException("Ожидалось 8 столбцов.");
            String subjectCode = row.get("subject_code").strip().toUpperCase(Locale.ROOT);
            var subject = subjects.get(subjectCode);
            if (subject == null)
              throw new ValidationException("Неизвестная дисциплина: " + subjectCode);
            var topic =
                topics.get(
                    subjectCode + "\n" + row.get("topic_code").strip().toUpperCase(Locale.ROOT));
            if (topic == null)
              throw new ValidationException("Неизвестная тема: " + row.get("topic_code"));
            var type = QuestionType.valueOf(row.get("type").strip().toUpperCase(Locale.ROOT));
            var question =
                new Question(
                    0,
                    topic.id(),
                    row.get("text"),
                    type,
                    Integer.parseInt(row.get("difficulty").strip()),
                    Integer.parseInt(row.get("importance").strip()),
                    parsePoints(row.get("max_points")),
                    row.get("answer_guidance"),
                    false,
                    0);
            banks.get(subject.id()).add(question);
            questions.add(question);
          } catch (ValidationException e) {
            errors.add("Запись " + number + ": " + e.getMessage());
          } catch (IllegalArgumentException e) {
            errors.add("Запись " + number + ": проверьте тип THEORY/PRACTICE и числовые поля.");
          }
        }
      }
      if (questions.isEmpty() && errors.isEmpty()) errors.add("Файл не содержит вопросов.");
    } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
      errors.add(
          "Не удалось прочитать CSV: проверьте UTF-8, кавычки и разделитель ;. " + e.getMessage());
    }
    return new ImportPreview(questions, errors);
  }

  public static BigDecimal parsePoints(String input) {
    String value = input == null ? "" : input.strip();
    if (!value.matches("[0-9]+([.,][0-9]{1,2})?"))
      throw new ValidationException("Баллы: число с максимум двумя знаками после запятой.");
    return new BigDecimal(value.replace(',', '.'));
  }

  private ImportPreview failure(String message) {
    return new ImportPreview(List.of(), List.of(message));
  }
}
