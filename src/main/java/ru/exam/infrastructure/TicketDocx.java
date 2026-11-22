package ru.exam.infrastructure;

import java.awt.Font;
import java.awt.font.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.*;
import java.text.AttributedString;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import ru.exam.domain.TicketSet;
import ru.exam.domain.ValidationException;

public final class TicketDocx {
  private static final String FONT = "Times New Roman";
  private static final int FONT_SIZE = 12;
  private static final int MARGIN = 850;
  private static final int BLOCK_HEIGHT = 7100;
  private static final int WIDTH = 11906 - 2 * MARGIN;
  private static final Font MEASURE_FONT = new Font(FONT, Font.BOLD, FONT_SIZE);
  private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);

  private TicketDocx() {}

  public static void checkLayout(TicketSet set) {
    var items = set.items();
    var overflow = new ArrayList<Integer>();
    for (int i = 0; i < set.config().count(); i++) {
      int lines =
          lines(set.header().organization())
              + lines(set.subject())
              + lines(set.header().period())
              + lines(set.header().signature())
              + 3;
      if (!set.header().approval().isBlank()) lines += lines(set.header().approval());
      for (int pos = 0; pos < set.config().size(); pos++)
        lines +=
            lines(
                (pos + 1)
                    + ". "
                    + items.get(set.selected().get(i * set.config().size() + pos)).text());
      if (lines > 21) overflow.add(i + 1);
    }
    if (!overflow.isEmpty())
      throw new ValidationException(
          "В макете «2 билета на А4» может не поместиться текст билетов "
              + overflow
              + ". Сократите шапку или замените длинные вопросы в черновике.");
  }

  private static int lines(String text) {
    int count = 0;
    for (var part : text.split("\\R", -1)) {
      if (part.isEmpty()) {
        count++;
        continue;
      }
      if (!part.codePoints()
          .allMatch(
              cp ->
                  cp == 9
                      || cp >= 32 && (cp < 0xD800 || cp > 0xDFFF && cp < 0xFFFE || cp >= 0x10000)))
        throw new ValidationException("В тексте билета есть недопустимые символы.");
      String expanded = part.replace("\t", "    ");
      var attributed = new AttributedString(expanded);
      attributed.addAttribute(TextAttribute.FONT, MEASURE_FONT);
      var measure = new LineBreakMeasurer(attributed.getIterator(), FONT_CONTEXT);
      while (measure.getPosition() < expanded.length()) {
        measure.nextLayout(435);
        count++;
      }
    }
    return count;
  }

  public static void write(TicketSet set, Path destination) throws IOException {
    checkLayout(set);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      throw new ValidationException("Файл уже существует. Выберите другое имя.");
    Path temporary = Files.createTempFile(destination.getParent(), ".tickets-", ".docx");
    try {
      try (var document = new XWPFDocument()) {
        var section = document.getDocument().getBody().addNewSectPr();
        var page = section.addNewPgSz();
        page.setW(BigInteger.valueOf(11906));
        page.setH(BigInteger.valueOf(16838));
        var margins = section.addNewPgMar();
        var margin = BigInteger.valueOf(MARGIN);
        margins.setTop(margin);
        margins.setBottom(margin);
        margins.setLeft(margin);
        margins.setRight(margin);
        margins.setHeader(BigInteger.ZERO);
        margins.setFooter(BigInteger.ZERO);
        margins.setGutter(BigInteger.ZERO);
        var items = set.items();
        for (int i = 0; i < set.config().count(); i += 2) {
          if (i > 0) {
            var separator = document.createParagraph();
            separator.setSpacingAfter(0);
            separator.setSpacingBefore(0);
            separator.setSpacingBetween(2, LineSpacingRule.EXACT);
            separator.createRun().addBreak(BreakType.PAGE);
          }
          var table = document.createTable(2, 1);
          table.setWidth(WIDTH);
          table.setTableAlignment(TableRowAlign.CENTER);
          table.setCellMargins(100, 160, 100, 160);
          for (int half = 0; half < 2; half++) {
            var row = table.getRow(half);
            row.setCantSplitRow(true);
            row.setHeight(BLOCK_HEIGHT);
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
            var cell = row.getCell(0);
            int number = i + half + 1;
            if (number > set.config().count()) continue;
            var header = set.header();
            paragraph(cell.getParagraphs().getFirst(), header.organization(), true, true);
            paragraph(cell.addParagraph(), set.subject(), false, true);
            paragraph(cell.addParagraph(), header.period(), false, true);
            if (!header.approval().isBlank())
              paragraph(cell.addParagraph(), header.approval(), false, true);
            paragraph(cell.addParagraph(), "Экзаменационный билет № " + number, true, true);
            for (int slot = 0; slot < set.config().size(); slot++) {
              var item = items.get(set.selected().get((number - 1) * set.config().size() + slot));
              paragraph(cell.addParagraph(), (slot + 1) + ". " + item.text(), false, false);
            }
            paragraph(
                cell.addParagraph(), header.signature() + " __________________", false, false);
          }
        }
        // Word reserves a paragraph after the final table. Keep that paragraph inside the page.
        var end = document.createParagraph();
        end.setSpacingBefore(0);
        end.setSpacingAfter(0);
        end.setSpacingBetween(2, LineSpacingRule.EXACT);
        try (var output = Files.newOutputStream(temporary)) {
          document.write(output);
        }
      }
      Files.move(temporary, destination);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void paragraph(
      XWPFParagraph paragraph, String text, boolean bold, boolean center) {
    paragraph.setSpacingAfter(0);
    paragraph.setSpacingBefore(0);
    paragraph.setSpacingBetween(14, LineSpacingRule.EXACT);
    if (center) paragraph.setAlignment(ParagraphAlignment.CENTER);
    var run = paragraph.createRun();
    run.setFontFamily(FONT);
    run.setFontSize(FONT_SIZE);
    run.setBold(bold);
    String[] lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) run.addBreak();
      run.setText(lines[i].replace("\t", "    "));
    }
  }
}
