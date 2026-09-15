package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewRegressionTest {
  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void explainDmlDoesNotReportExecutedWrites(DorisVersion version) {
    var parser = new DorisSqlParser(version);
    for (String sql :
        java.util.List.of(
            "EXPLAIN INSERT INTO dst SELECT * FROM src",
            "EXPLAIN UPDATE dst SET v = 1 WHERE id = 2",
            "EXPLAIN DELETE FROM dst WHERE id = 2")) {
      var result = parser.parseStatement(sql);
      assertTrue(result.explained());
      assertTrue(result.outputTables().isEmpty(), sql);
      assertFalse(result.inputTables().isEmpty(), sql);
    }
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void keywordNamesDoNotShadowDdlClauses(DorisVersion version) {
    var parser = new DorisSqlParser(version);
    assertEquals(
        "hello",
        parser
            .parseStatement("CREATE TABLE comment (id INT) COMMENT 'hello'")
            .tableDefinition()
            .comment());
    var buckets =
        parser
            .parseStatement("CREATE TABLE buckets (id INT) DISTRIBUTED BY HASH(id) BUCKETS 8")
            .tableDefinition();
    assertEquals("8", buckets.buckets());
    assertEquals("HASH", buckets.distributionType());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void dedicatedDateFunctionsAreCollected(DorisVersion version) {
    var result =
        new DorisSqlParser(version)
            .parseStatement(
                "SELECT DATE_ADD(d, INTERVAL 1 DAY), TIMESTAMPDIFF(DAY,a,b), CURRENT_TIMESTAMP FROM"
                    + " t");
    assertTrue(
        result
            .functionNames()
            .containsAll(java.util.List.of("DATE_ADD", "TIMESTAMPDIFF", "CURRENT_TIMESTAMP")),
        result.functionNames().toString());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void defaultsRetainInternalSourceText(DorisVersion version) {
    var result =
        new DorisSqlParser(version)
            .parseStatement(
                "CREATE TABLE t (id DATETIME DEFAULT CURRENT_TIMESTAMP /* precision */ ( 3 ))");
    assertEquals(
        "CURRENT_TIMESTAMP /* precision */ ( 3 )",
        result.tableDefinition().columns().get(0).defaultExpression());
  }
}
