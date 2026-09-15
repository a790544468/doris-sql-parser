package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ParserTest {
  @Test
  void versionBoundaries() {
    var old = new DorisSqlParser(DorisVersion.DORIS_2_1);
    var modern = new DorisSqlParser(DorisVersion.DORIS_4_0);
    old.checkSqlSyntax("SELECT analyzer FROM t");
    old.checkSqlSyntax("STOP SYNC JOB j");
    assertThrows(SqlParseException.class, () -> modern.checkSqlSyntax("SELECT analyzer FROM t"));
    assertThrows(SqlParseException.class, () -> modern.checkSqlSyntax("STOP SYNC JOB j"));
    modern.checkSqlSyntax("SELECT TRY_CAST(v AS INT) FROM t");
    assertThrows(
        SqlParseException.class, () -> old.checkSqlSyntax("SELECT TRY_CAST(v AS INT) FROM t"));
  }

  @Test
  void strictErrorsAndScripts() {
    var p = new DorisSqlParser();
    assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("SELECT FROM"));
    assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("SELECT 1; SELECT 2"));
    assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("-- empty"));
    assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("SELECT 'unclosed"));
    assertThrows(SqlParseException.class, () -> p.checkSqlSyntax("SELECT 1 /* unclosed"));
    assertEquals(
        java.util.List.of("select ';'", "select '你好😀'"),
        p.splitSql("select ';'; -- hello;\nselect '你好😀';"));
    assertEquals(2, p.parseMultiStatement("SELECT 1; SELECT 2;").size());
  }

  @Test
  void expressionAndCase() {
    var p = new DorisSqlParser();
    assertTrue(p.parseExpression("price * 2 + 1").contains("expression"));
    assertThrows(SqlParseException.class, () -> p.parseExpression("1 +"));
    assertTrue(p.sqlKeywords().contains("SELECT"));
    assertTrue(p.parseTree("select 'MiXeD'").contains("MiXeD"));
  }
}
