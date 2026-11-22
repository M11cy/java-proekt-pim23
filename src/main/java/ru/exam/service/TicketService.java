package ru.exam.service;

import static ru.exam.domain.TicketSet.*;
import static ru.exam.infrastructure.JdbcSupport.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import ru.exam.domain.*;
import ru.exam.infrastructure.*;

public final class TicketService {
  public record Summary(long id, String name, int count, boolean approved) {}

  private final Database database;
  private final AccessControl access;

  public TicketService(Database database, AccessControl access) {
    this.database = database;
    this.access = access;
  }

  public List<Item> bank(long subjectId) {
    return database.read(c -> bank(c, subjectId));
  }

  private List<Item> bank(Connection c, long subjectId) throws SQLException {
    access.subject(c, subjectId);
    return list(
        c,
        """
        SELECT q.id,r.revision_no,t.id,t.name,r.type,r.difficulty,r.max_points_scaled,
               coalesce(k.score,50),r.text
        FROM questions q JOIN question_revisions r
          ON r.question_id=q.id AND r.revision_no=q.current_revision
        JOIN topics t ON t.id=q.topic_id
        LEFT JOIN question_rankings k ON k.question_id=q.id AND k.revision=r.revision_no
        WHERE t.subject_id=? AND q.archived=0 ORDER BY q.id
        """,
        TicketService::item,
        subjectId);
  }

  public List<Summary> summaries(long subjectId) {
    return database.read(
        c -> {
          access.subject(c, subjectId);
          return list(
              c,
              """
              SELECT id,name,ticket_count,approved_at IS NOT NULL
              FROM ticket_sets WHERE subject_id=? ORDER BY id DESC
              """,
              r -> new Summary(r.getLong(1), r.getString(2), r.getInt(3), r.getBoolean(4)),
              subjectId);
        });
  }

  public TicketSet generate(long subjectId, String name, Config config) {
    String title = Organization.text(name, "Название комплекта", 120);
    return database.transaction(
        c -> {
          var bank = bank(c, subjectId);
          String subject =
              list(c, "SELECT name FROM subjects WHERE id=?", r -> r.getString(1), subjectId)
                  .getFirst();
          var result = new TicketOptimizer(config, bank).generate();
          execute(
              c,
              """
INSERT INTO ticket_sets(subject_id,subject_name,name,algorithm,created_at,ticket_count,
repeat_limit,target,deviation,seed,budget,required,excluded,topic_quotas,set_quotas,
type_quotas,weights,initial,selected) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
              subjectId,
              subject,
              title,
              TicketOptimizer.VERSION,
              Instant.now().toString(),
              config.count(),
              config.repeat(),
              config.target(),
              config.deviation(),
              config.seed(),
              config.budget(),
              join(new TreeSet<>(config.required())),
              join(new TreeSet<>(config.excluded())),
              encode(config.topicQuotas()),
              config.topicQuotasPerSet(),
              encode(config.typeQuotas()),
              join(config.weights()),
              join(result.initial()),
              join(result.best()));
          long id = lastId(c);
          for (int i = 0; i < config.size(); i++) {
            var slot = config.slots().get(i);
            execute(
                c,
                "INSERT INTO ticket_slots VALUES(?,?,?,?,?,?)",
                id,
                i,
                slot.topic(),
                slot.type() == null ? null : slot.type().name(),
                slot.min(),
                slot.max());
          }
          try (var statement =
              c.prepareStatement("INSERT INTO ticket_bank_snapshot VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (var item : bank) {
              statement.setLong(1, id);
              statement.setLong(2, item.id());
              statement.setInt(3, item.revision());
              statement.setLong(4, item.topicId());
              statement.setString(5, item.topic());
              statement.setString(6, item.type().name());
              statement.setInt(7, item.difficulty());
              statement.setLong(8, item.points().movePointRight(2).longValueExact());
              statement.setDouble(9, item.rank());
              statement.setString(10, item.text());
              statement.addBatch();
            }
            statement.executeBatch();
          }
          var set =
              new TicketSet(
                  id,
                  subjectId,
                  subject,
                  title,
                  config,
                  bank,
                  result.initial(),
                  result.best(),
                  null,
                  1);
          audit(c, set, "GENERATE_TICKETS", "Создан черновик. Seed " + config.seed());
          return set;
        });
  }

  public TicketSet load(long id) {
    return database.read(c -> load(c, id));
  }

  private TicketSet load(Connection c, long id) throws SQLException {
    var ids = list(c, "SELECT subject_id FROM ticket_sets WHERE id=?", r -> r.getLong(1), id);
    if (ids.isEmpty()) throw new ValidationException("Комплект не найден.");
    long subjectId = ids.getFirst();
    access.subject(c, subjectId);
    var bank =
        list(
            c,
            """
            SELECT question_id,revision,topic_id,topic_name,type,difficulty,points_scaled,rank,text
            FROM ticket_bank_snapshot WHERE set_id=? ORDER BY question_id
            """,
            TicketService::item,
            id);
    var slots =
        list(
            c,
            """
            SELECT topic_id,type,min_difficulty,max_difficulty FROM ticket_slots
            WHERE set_id=? ORDER BY position
            """,
            r ->
                new Slot(
                    r.getObject(1) == null ? null : r.getLong(1),
                    r.getString(2) == null ? null : QuestionType.valueOf(r.getString(2)),
                    r.getInt(3),
                    r.getInt(4)),
            id);
    return list(
            c,
            "SELECT * FROM ticket_sets WHERE id=?",
            r -> {
              if (!TicketOptimizer.VERSION.equals(r.getString("algorithm")))
                throw new ValidationException("Версия алгоритма комплекта не поддерживается.");
              var config =
                  new Config(
                      r.getInt("ticket_count"),
                      slots,
                      r.getInt("repeat_limit"),
                      r.getDouble("target"),
                      r.getObject("deviation") == null ? null : r.getDouble("deviation"),
                      r.getLong("seed"),
                      r.getInt("budget"),
                      Set.copyOf(longs(r.getString("required"))),
                      Set.copyOf(longs(r.getString("excluded"))),
                      decode(r.getString("topic_quotas"), Long::valueOf),
                      r.getBoolean("set_quotas"),
                      decode(r.getString("type_quotas"), QuestionType::valueOf),
                      Arrays.stream(r.getString("weights").split(","))
                          .map(Double::valueOf)
                          .toList());
              Header header =
                  r.getString("approved_at") == null
                      ? null
                      : new Header(
                          r.getString("organization"),
                          r.getString("period"),
                          r.getString("signature"),
                          r.getString("approval"));
              return new TicketSet(
                  id,
                  subjectId,
                  r.getString("subject_name"),
                  r.getString("name"),
                  config,
                  bank,
                  longs(r.getString("initial")),
                  longs(r.getString("selected")),
                  header,
                  r.getInt("version"));
            },
            id)
        .getFirst();
  }

  public TicketSet replace(long id, int version, int index, long questionId) {
    return database.transaction(
        c -> {
          var set = draft(c, id, version);
          var selection = new ArrayList<>(set.selected());
          if (index < 0 || index >= selection.size())
            throw new ValidationException("Выберите позицию.");
          selection.set(index, questionId);
          new TicketOptimizer(set.config(), set.bank()).validate(selection);
          saveSelection(c, set, selection);
          audit(
              c,
              set,
              "EDIT_TICKETS",
              "Замена вопроса в билете " + (index / set.config().size() + 1));
          return load(c, id);
        });
  }

  public TicketSet improve(long id, int version) {
    return database.transaction(
        c -> {
          var set = draft(c, id, version);
          saveSelection(
              c, set, new TicketOptimizer(set.config(), set.bank()).improve(set.selected()));
          audit(c, set, "BALANCE_TICKETS", "Выполнена балансировка черновика");
          return load(c, id);
        });
  }

  public TicketSet approve(long id, int version, Header header) {
    return database.transaction(
        c -> {
          var set = draft(c, id, version);
          new TicketOptimizer(set.config(), set.bank()).validate(set.selected());
          var approved =
              new TicketSet(
                  set.id(),
                  set.subjectId(),
                  set.subject(),
                  set.name(),
                  set.config(),
                  set.bank(),
                  set.initial(),
                  set.selected(),
                  header,
                  version + 1);
          TicketDocx.checkLayout(approved);
          execute(
              c,
              """
              UPDATE ticket_sets SET organization=?,period=?,signature=?,approval=?,approved_at=?,
              version=version+1 WHERE id=?
              """,
              header.organization(),
              header.period(),
              header.signature(),
              header.approval(),
              Instant.now().toString(),
              id);
          audit(c, set, "APPROVE_TICKETS", "Комплект утверждён");
          return approved;
        });
  }

  public Path export(long id, Path destination) {
    Path path = destination.toAbsolutePath().normalize();
    Path written = null;
    try {
      synchronized (database) {
        var set = load(id);
        if (!set.approved()) throw new ValidationException("Сначала утвердите комплект.");
        TicketDocx.write(set, path);
        written = path;
        database.transaction(
            c -> {
              access.subject(c, set.subjectId());
              RankingService.recordWordUsage(c, id, set.selected());
              audit(c, set, "EXPORT_TICKETS", "Экспорт Word");
              return null;
            });
      }
      return path;
    } catch (IOException | RuntimeException e) {
      if (written != null) {
        try {
          Files.deleteIfExists(written);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      if (e instanceof ValidationException validation) throw validation;
      throw new StorageException("Не удалось экспортировать Word: " + e.getMessage(), e);
    }
  }

  private TicketSet draft(Connection c, long id, int version) throws SQLException {
    var set = load(c, id);
    if (set.approved()) throw new ValidationException("Утверждённый комплект зафиксирован.");
    if (set.version() != version)
      throw new ValidationException("Комплект изменился. Откройте его снова.");
    return set;
  }

  private void saveSelection(Connection c, TicketSet set, List<Long> selection)
      throws SQLException {
    execute(
        c,
        "UPDATE ticket_sets SET selected=?,version=version+1 WHERE id=?",
        join(selection),
        set.id());
  }

  private void audit(Connection c, TicketSet set, String action, String details)
      throws SQLException {
    AuditLog.write(
        c,
        access.require(c).login(),
        action,
        "ticket_set",
        set.id(),
        set.subjectId(),
        "SUCCESS",
        set.name() + ". " + details);
  }

  private static Item item(ResultSet r) throws SQLException {
    return new Item(
        r.getLong(1),
        r.getInt(2),
        r.getLong(3),
        r.getString(4),
        QuestionType.valueOf(r.getString(5)),
        r.getInt(6),
        BigDecimal.valueOf(r.getLong(7), 2),
        r.getDouble(8),
        r.getString(9));
  }

  private static String join(Collection<?> values) {
    return values.stream().map(Object::toString).collect(Collectors.joining(","));
  }

  private static List<Long> longs(String text) {
    return text.isEmpty() ? List.of() : Arrays.stream(text.split(",")).map(Long::valueOf).toList();
  }

  private static String encode(Map<?, Integer> values) {
    return values.entrySet().stream()
        .map(
            e ->
                e.getKey() instanceof Enum<?> type
                    ? type.name() + "=" + e.getValue()
                    : e.getKey() + "=" + e.getValue())
        .sorted()
        .collect(Collectors.joining(","));
  }

  private static <T> Map<T, Integer> decode(String text, Function<String, T> parse) {
    var result = new HashMap<T, Integer>();
    if (!text.isEmpty())
      for (String pair : text.split(",")) {
        String[] parts = pair.split("=", 2);
        result.put(parse.apply(parts[0]), Integer.valueOf(parts[1]));
      }
    return result;
  }
}
