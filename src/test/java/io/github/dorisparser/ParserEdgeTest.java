package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ParserEdgeTest {
  @Test
  void commentsAndUnicodePreserved() {
    for (var v : DorisVersion.values()) {
      var p = new DorisSqlParser(v);
      assertEquals(
          List.of("SELECT 'a;😀', `中文.字段` /* inside; */ FROM `目录`.`表`", "SELECT 2"),
          p.splitSql(
              ";; -- leading;\n"
                  + "SELECT 'a;😀', `中文.字段` /* inside; */ FROM `目录`.`表`; SELECT 2; -- end"));
      assertEquals(List.of(), p.splitSql("; -- comment\n /* empty */ ;"));
    }
  }

  @Test
  void keepsHiddenDualSource() {
    assertEquals(
        List.of("select 1 from dual"), new DorisSqlParser().splitSql("select 1 from dual;"));
  }

  @Test
  void errorsCarryGlobalScriptCoordinates() {
    for (var v : DorisVersion.values()) {
      var ex =
          assertThrows(
              SqlParseException.class,
              () -> new DorisSqlParser(v).parseMultiStatement("SELECT 1;\nSELECT FROM"));
      assertEquals(2, ex.line());
      assertTrue(ex.column() > 1);
      assertEquals(v, ex.version());
      assertEquals("SELECT 1;\nSELECT FROM", ex.sql());
    }
  }

  @Test
  void rejectsTrailingAndLexerErrors() {
    for (var v : DorisVersion.values())
      for (String sql :
          List.of(
              "SELECT 1 #",
              "SELECT `broken",
              "SELECT 'broken",
              "SELECT 1 /* broken",
              "/* broken",
              "SELECT 1 /* outer /* inner */"))
        assertThrows(
            SqlParseException.class,
            () -> new DorisSqlParser(v).checkSqlSyntax(sql),
            v + " " + sql);
  }

  @Test
  void facadeIsReusableAcrossThreads() throws Exception {
    var parser = new DorisSqlParser();
    var executor = Executors.newFixedThreadPool(4);
    try {
      List<Callable<List<String>>> work = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        int n = i;
        work.add(() -> parser.splitSql("SELECT " + n + "; SELECT ';'"));
      }
      var results = executor.invokeAll(work);
      for (int i = 0; i < 40; i++)
        assertEquals(List.of("SELECT " + i, "SELECT ';'"), results.get(i).get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void unquotedHyphenIdentifierIsRejected() {
    for (var v : DorisVersion.values()) {
      var p = new DorisSqlParser(v);
      assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("SELECT * FROM bad-name"));
      p.checkSqlSyntax("SELECT * FROM `bad-name`");
    }
  }

  @Test
  void optionValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DorisSqlParser(DorisVersion.DORIS_2_1, new ParserOptions(false, true)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DorisSqlParser(DorisVersion.DORIS_2_1, new ParserOptions(true, false)));
  }
}
