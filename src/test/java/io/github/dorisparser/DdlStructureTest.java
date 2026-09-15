package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DdlStructureTest {
  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void userExampleAndColumnDetails(DorisVersion version) {
    var d =
        new DorisSqlParser(version)
            .parseStatement(
                """
                CREATE TABLE IF NOT EXISTS user_log (
                  log_id BIGINT NOT NULL COMMENT '日志ID', user_id BIGINT NOT NULL,
                  event_time DATETIME NOT NULL, action VARCHAR(50), ip_addr STRING
                ) DUPLICATE KEY(log_id, user_id, event_time)
                PARTITION BY RANGE(event_time) (
                  PARTITION p20231001 VALUES LESS THAN ('2023-10-02'),
                  PARTITION p20231002 VALUES LESS THAN ('2023-10-03')
                ) DISTRIBUTED BY HASH(user_id) BUCKETS 10 PROPERTIES ('replication_num'='1');
                """)
            .tableDefinition();
    assertEquals(5, d.columns().size());
    assertEquals("日志ID", d.columns().get(0).comment());
    assertEquals(List.of("log_id", "user_id", "event_time"), d.sortColumns());
    assertEquals("RANGE", d.partition().type());
    assertEquals(List.of("event_time"), d.partition().columns());
    assertEquals("p20231001", d.partition().partitions().get(0).name());
    assertEquals("LESS_THAN", d.partition().partitions().get(0).kind());
    assertEquals("2023-10-02", d.partition().partitions().get(0).upperBound().get(0).value());
    assertEquals("HASH", d.distribution().type());
    assertEquals(List.of("user_id"), d.distribution().columns());
    assertEquals("10", d.distribution().buckets());
    assertFalse(d.distribution().autoBuckets());
    assertEquals("1", d.properties().get("replication_num"));
    assertEquals("'replication_num'", d.propertyItems().get(0).keySql());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void indexesRollupsClusterPropertiesAndColumnExtras(DorisVersion version) {
    var d =
        new DorisSqlParser(version)
            .parseStatement(
                """
                CREATE TABLE t (id BIGINT NOT NULL AUTO_INCREMENT(100) COMMENT '编号',
                  ts DATETIME DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  v INT,
                  INDEX idx (id) USING INVERTED PROPERTIES ('parser'='english') COMMENT '检索')
                UNIQUE KEY(id) CLUSTER BY(ts, id) COMMENT 'table'
                DISTRIBUTED BY RANDOM BUCKETS AUTO
                ROLLUP (r1(id,ts) DUPLICATE KEY(id) PROPERTIES ('k'='v'))
                PROPERTIES ('replication_num'='1', 'a'='first', 'a'='last')
                BROKER PROPERTIES ('path'='x')
                """)
            .tableDefinition();
    assertEquals(List.of("ts", "id"), d.clusterKeys());
    assertEquals(List.of("ts", "id"), d.sortColumns());
    assertEquals("100", d.columns().get(0).autoIncrementStart());
    assertEquals("CURRENT_TIMESTAMP(3)", d.columns().get(1).onUpdateExpression());
    assertNull(d.columns().get(2).generatedExpression());
    if (version == DorisVersion.DORIS_4_0)
      assertEquals(
          "id + 1",
          new DorisSqlParser(version)
              .parseStatement("CREATE TABLE generated_t(id INT, v INT AS (id + 1))")
              .tableDefinition()
              .columns()
              .get(1)
              .generatedExpression());
    assertEquals("dataType", d.columns().get(0).typeSyntax().rule());
    assertEquals("idx", d.indexes().get(0).name());
    assertEquals("INVERTED", d.indexes().get(0).type());
    assertEquals("检索", d.indexes().get(0).comment());
    assertEquals("english", d.indexes().get(0).properties().get("parser"));
    assertEquals("r1", d.rollups().get(0).name());
    assertEquals(List.of("id", "ts"), d.rollups().get(0).columns());
    assertEquals(List.of("id"), d.rollups().get(0).duplicateKeys());
    assertEquals("v", d.rollups().get(0).properties().get("k"));
    assertEquals(3, d.propertyItems().size());
    assertEquals("last", d.properties().get("a"));
    assertEquals("x", d.externalProperties().get("path"));
    assertTrue(d.distribution().autoBuckets());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void rangeVariantsAndPerPartitionProperties(DorisVersion version) {
    var parser = new DorisSqlParser(version);
    var p =
        parser
            .parseStatement(
                """
                CREATE TABLE t (k INT) PARTITION BY RANGE(k) (
                PARTITION p1 VALUES [(-10), (0)) ('replication_num'='1'),
                PARTITION pmax VALUES LESS THAN MAXVALUE)
                """)
            .tableDefinition()
            .partition();
    assertEquals("FIXED_RANGE", p.partitions().get(0).kind());
    assertEquals("-10", p.partitions().get(0).lowerBound().get(0).value());
    assertEquals("0", p.partitions().get(0).upperBound().get(0).value());
    assertEquals("1", p.partitions().get(0).properties().get("replication_num"));
    assertEquals("MAXVALUE", p.partitions().get(1).upperBound().get(0).kind());
    var step =
        parser
            .parseStatement(
                "CREATE TABLE t(k DATE) PARTITION BY RANGE(k) (FROM ('2023-01-01') TO"
                    + " ('2024-01-01') INTERVAL 1 MONTH)")
            .tableDefinition()
            .partition()
            .partitions()
            .get(0);
    assertEquals("STEP", step.kind());
    assertEquals("2023-01-01", step.lowerBound().get(0).value());
    assertEquals("1", step.intervalAmount());
    assertEquals("MONTH", step.intervalUnit());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void listTuplesAutomaticAndAbsentClauses(DorisVersion version) {
    var parser = new DorisSqlParser(version);
    var p =
        parser
            .parseStatement(
                "CREATE TABLE t(a STRING,b INT) PARTITION BY LIST(a,b) (PARTITION p VALUES IN"
                    + " (('a',1),('b',NULL)))")
            .tableDefinition()
            .partition();
    var values = p.partitions().get(0).inValues();
    assertEquals(2, values.size());
    assertEquals("a", values.get(0).get(0).value());
    assertEquals("NULL", values.get(1).get(1).kind());
    assertNull(values.get(1).get(1).value());
    var auto =
        parser
            .parseStatement(
                "CREATE TABLE t(k DATETIME) AUTO PARTITION BY RANGE(date_trunc(k,'day')) ()")
            .tableDefinition()
            .partition();
    assertTrue(auto.automatic());
    assertEquals("date_trunc(k,'day')", auto.expressions().get(0).text());
    assertEquals(List.of(), auto.columns());
    var empty = parser.parseStatement("CREATE TABLE x(id INT)").tableDefinition();
    assertNull(empty.partition());
    assertNull(empty.distribution());
    assertNull(empty.columns().get(0).nullable());
    assertNull(empty.columns().get(0).comment());
    assertThrows(UnsupportedOperationException.class, () -> values.get(0).clear());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void singleColumnListsQuotedKeywordsAndMultiplePropertyScopes(DorisVersion version) {
    var d =
        new DorisSqlParser(version)
            .parseStatement(
                """
                CREATE TABLE `buckets` (`comment` INT DEFAULT - /*neg*/ 2 COMMENT 'a\\nline',
                  INDEX IF NOT EXISTS idx (`comment`) USING INVERTED)
                PARTITION BY LIST(`comment`) (PARTITION p VALUES IN (1,2,3))
                DISTRIBUTED BY HASH(`comment`) BUCKETS 4
                """)
            .tableDefinition();
    assertEquals("- /*neg*/ 2", d.columns().get(0).defaultExpression());
    assertEquals("a\nline", d.columns().get(0).comment());
    assertTrue(d.indexes().get(0).ifNotExists());
    assertEquals(3, d.partition().partitions().get(0).inValues().size());
    assertEquals("2", d.partition().partitions().get(0).inValues().get(1).get(0).value());
    assertEquals("4", d.distribution().buckets());
  }

  @org.junit.jupiter.api.Test
  void fourZeroNoBackslashEscapesAppliesToNestedProperties() {
    var d =
        new DorisSqlParser(DorisVersion.DORIS_4_0, new ParserOptions(true, false))
            .parseStatement(
                """
                CREATE TABLE t(a STRING, INDEX idx(a) USING INVERTED PROPERTIES ('k'='x\\ny'))
                PARTITION BY LIST(a) (PARTITION p VALUES IN ('x\\ny') ('k'='x\\ny'))
                """)
            .tableDefinition();
    assertEquals("x\\ny", d.indexes().get(0).properties().get("k"));
    assertEquals("x\\ny", d.partition().partitions().get(0).inValues().get(0).get(0).value());
    assertEquals("x\\ny", d.partition().partitions().get(0).properties().get("k"));
  }
}
