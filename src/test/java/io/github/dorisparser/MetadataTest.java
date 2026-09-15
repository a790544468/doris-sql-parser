package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dorisparser.model.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MetadataTest {
  private SqlStatement parse(DorisVersion version, String sql) {
    return new DorisSqlParser(version).parseStatement(sql);
  }

  private List<String> names(List<TableId> tables) {
    return tables.stream().map(TableId::qualifiedName).toList();
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void selectKeepsQuotedDotsAndOuterLimit(DorisVersion version) {
    var s =
        parse(
            version,
            "SELECT SUM(x.v), CAST(1 AS INT) FROM `db.with.dot`.`t.x` x JOIN (SELECT * FROM inner_t"
                + " LIMIT 2) y ON x.id=y.id LIMIT 5 OFFSET 3");
    assertEquals(StatementType.SELECT, s.statementType());
    assertEquals(List.of("`db.with.dot`.`t.x`", "`inner_t`"), names(s.inputTables()));
    assertEquals(List.of("db.with.dot", "t.x"), s.inputTables().get(0).parts());
    assertEquals(5L, s.limit());
    assertEquals(3L, s.offset());
    assertTrue(s.functionNames().containsAll(List.of("SUM", "CAST")));
    assertFalse(s.functionNames().contains("SELECT"));
    assertNull(parse(version, "SELECT * FROM (SELECT * FROM t LIMIT 2) x").limit());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void ctesHaveLexicalVisibility(DorisVersion version) {
    var s =
        parse(
            version,
            "WITH first_cte AS (SELECT * FROM later_cte), later_cte AS (SELECT * FROM base_t),"
                + " last_cte AS (SELECT * FROM first_cte JOIN later_cte ON 1=1) SELECT * FROM"
                + " last_cte JOIN db.later_cte ON 1=1");
    assertEquals(List.of("`later_cte`", "`base_t`", "`db`.`later_cte`"), names(s.inputTables()));
    var nested =
        parse(
            version,
            "WITH x AS (SELECT * FROM base_t) SELECT * FROM x JOIN (WITH x AS (SELECT * FROM x)"
                + " SELECT * FROM x) n ON 1=1");
    assertEquals(List.of("`base_t`"), names(nested.inputTables()));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void dmlSeparatesReadsAndWrites(DorisVersion version) {
    var insert = parse(version, "INSERT INTO db.target SELECT * FROM src");
    assertEquals(StatementType.INSERT_SELECT, insert.statementType());
    assertEquals(List.of("`src`"), names(insert.inputTables()));
    assertEquals(List.of("`db`.`target`"), names(insert.outputTables()));
    assertEquals(StatementType.INSERT, parse(version, "INSERT INTO t VALUES (1)").statementType());
    assertEquals(
        StatementType.INSERT_OVERWRITE,
        parse(version, "INSERT OVERWRITE TABLE t SELECT * FROM s").statementType());
    var update = parse(version, "UPDATE target SET v=s.v FROM source_t s WHERE target.id=s.id");
    assertEquals(StatementType.UPDATE, update.statementType());
    assertEquals(List.of("`target`", "`source_t`"), names(update.inputTables()));
    assertEquals(List.of("`target`"), names(update.outputTables()));
    var delete = parse(version, "DELETE FROM target USING source_t s WHERE target.id=s.id");
    assertEquals(StatementType.DELETE, delete.statementType());
    assertEquals(List.of("`target`", "`source_t`"), names(delete.inputTables()));
    assertEquals(List.of("`target`"), names(delete.outputTables()));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void createTableExtractsDefinitions(DorisVersion version) {
    var s =
        parse(
            version,
            "CREATE TABLE IF NOT EXISTS db.t (id BIGINT NOT NULL COMMENT '标识', amount DECIMAL(12,2)"
                + " SUM DEFAULT '0' COMMENT '金额') ENGINE=OLAP AGGREGATE KEY(id) COMMENT '测试表'"
                + " DISTRIBUTED BY HASH(id) BUCKETS 8 PROPERTIES ('replication_num'='1')");
    assertEquals(StatementType.CREATE_TABLE, s.statementType());
    assertTrue(s.inputTables().isEmpty());
    assertEquals(List.of("`db`.`t`"), names(s.outputTables()));
    var d = s.tableDefinition();
    assertNotNull(d);
    assertTrue(d.ifNotExists());
    assertEquals("OLAP", d.engine());
    assertEquals("AGGREGATE", d.keyType());
    assertEquals(List.of("id"), d.keyColumns());
    assertEquals("测试表", d.comment());
    assertEquals("HASH", d.distributionType());
    assertEquals(List.of("id"), d.distributionColumns());
    assertEquals("8", d.buckets());
    assertEquals("1", d.properties().get("replication_num"));
    assertEquals(2, d.columns().size());
    var id = d.columns().get(0);
    assertEquals("id", id.name());
    assertEquals("BIGINT", id.dataType());
    assertEquals(false, id.nullable());
    assertTrue(id.key());
    var amount = d.columns().get(1);
    assertEquals("DECIMAL(12,2)", amount.dataType());
    assertEquals("SUM", amount.aggregateType());
    assertEquals("'0'", amount.defaultExpression());
    assertEquals("金额", amount.comment());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void ctasLikeAndViewsHaveDirections(DorisVersion version) {
    var ctas =
        parse(version, "CREATE TABLE dst DISTRIBUTED BY HASH(id) BUCKETS 1 AS SELECT id FROM src");
    assertEquals(StatementType.CREATE_TABLE_AS_SELECT, ctas.statementType());
    assertEquals(List.of("`src`"), names(ctas.inputTables()));
    assertEquals(List.of("`dst`"), names(ctas.outputTables()));
    assertEquals("SELECT id FROM src", ctas.tableDefinition().querySql());
    var like = parse(version, "CREATE TABLE dst LIKE db.src");
    assertEquals(StatementType.CREATE_TABLE_LIKE, like.statementType());
    assertEquals(List.of("`db`.`src`"), names(like.inputTables()));
    assertEquals(List.of("`dst`"), names(like.outputTables()));
    var view = parse(version, "CREATE VIEW v AS SELECT * FROM t");
    assertEquals(StatementType.CREATE_VIEW, view.statementType());
    assertEquals(List.of("`t`"), names(view.inputTables()));
    var mv = parse(version, "CREATE MATERIALIZED VIEW mv AS SELECT id FROM src");
    assertEquals(StatementType.CREATE_MATERIALIZED_VIEW, mv.statementType());
    assertEquals(List.of("`src`"), names(mv.inputTables()));
    assertEquals(List.of("`mv`"), names(mv.outputTables()));
    assertEquals(
        StatementType.DROP_MATERIALIZED_VIEW,
        parse(version, "DROP MATERIALIZED VIEW mv").statementType());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void alterAndCommonCommandsHaveHonestCoverage(DorisVersion version) {
    var alter = parse(version, "ALTER TABLE t ADD COLUMN n INT, DROP COLUMN old_col");
    assertEquals(StatementType.ALTER_TABLE, alter.statementType());
    assertEquals(2, alter.alterActions().size());
    assertEquals(List.of("`t`"), names(alter.outputTables()));
    assertEquals(StatementType.DROP_TABLE, parse(version, "DROP TABLE t").statementType());
    assertEquals(StatementType.SHOW, parse(version, "SHOW TABLES").statementType());
    assertEquals(StatementType.USE, parse(version, "USE db").statementType());
    var job =
        parse(version, "CREATE JOB j ON SCHEDULE EVERY 1 DAY DO INSERT INTO dst SELECT * FROM src");
    assertEquals(StatementType.OTHER, job.statementType());
    assertEquals(MetadataStatus.PARTIAL, job.metadataStatus());
    assertFalse(job.warnings().isEmpty());
    assertTrue(job.inputTables().isEmpty());
    assertTrue(job.outputTables().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void explainAndOverflowAreRepresentedHonestly(DorisVersion version) {
    var s = parse(version, "EXPLAIN SELECT * FROM t LIMIT 9223372036854775808");
    assertTrue(s.explained());
    assertEquals(StatementType.SELECT, s.statementType());
    assertNull(s.limit());
    assertEquals(MetadataStatus.PARTIAL, s.metadataStatus());
    assertFalse(s.warnings().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void valuesWithScalarSubqueryAreStillValuesInserts(DorisVersion version) {
    var s = parse(version, "INSERT INTO t VALUES ((SELECT id FROM src))");
    assertEquals(StatementType.INSERT, s.statementType());
    assertEquals(List.of("`src`"), names(s.inputTables()));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void loadSourcesAndTargetsAreExplicit(DorisVersion version) {
    var s = parse(version, "LOAD LABEL db.my_label (DATA FROM TABLE src INTO TABLE dst)");
    assertEquals(StatementType.LOAD, s.statementType());
    assertEquals(List.of("`src`"), names(s.inputTables()));
    assertEquals(List.of("`dst`"), names(s.outputTables()));
    assertEquals(MetadataStatus.PARTIAL, s.metadataStatus());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void duplicatePropertiesAreFlagged(DorisVersion version) {
    var s = parse(version, "CREATE TABLE t (id INT) PROPERTIES ('k'='one', 'k'='two')");
    assertEquals(MetadataStatus.PARTIAL, s.metadataStatus());
    assertFalse(s.warnings().isEmpty());
  }

  @org.junit.jupiter.api.Test
  void generatedExpressionNullDoesNotBecomeColumnNullability() {
    var s =
        parse(
            DorisVersion.DORIS_4_0,
            "CREATE TABLE t (a INT, b INT AS (IF(a IS NULL,0,a))) DISTRIBUTED BY RANDOM BUCKETS"
                + " AUTO");
    var b = s.tableDefinition().columns().get(1);
    assertNull(b.nullable());
    assertEquals("IF(a IS NULL,0,a)", b.generatedExpression());
    assertEquals("RANDOM", s.tableDefinition().distributionType());
    assertEquals("AUTO", s.tableDefinition().buckets());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void ansiLimitDoesNotChangeLegacyBranchMeaning(DorisVersion version) {
    String sql = "SELECT * FROM t UNION ALL SELECT * FROM s LIMIT 10";
    assertNull(parse(version, sql).limit());
    if (version == DorisVersion.DORIS_4_0) {
      var ansi = new DorisSqlParser(version, new ParserOptions(false, true)).parseStatement(sql);
      assertEquals(10L, ansi.limit());
    }
    assertNull(parse(version, "SELECT * FROM t UNION ALL (SELECT * FROM s LIMIT 10)").limit());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void stringLiteralMetadataUsesDorisEscapes(DorisVersion version) {
    String sql =
        "CREATE TABLE t (id INT COMMENT 'line\\n"
            + "next') COMMENT 'tab\\there' PROPERTIES ('path'='a\\\\b', 'quote'='it\\'s')";
    var d = parse(version, sql).tableDefinition();
    assertEquals("line\nnext", d.columns().get(0).comment());
    assertEquals("tab\there", d.comment());
    assertEquals("a\\b", d.properties().get("path"));
    assertEquals("it's", d.properties().get("quote"));
  }

  @org.junit.jupiter.api.Test
  void noBackslashEscapesPreservesLiteralBackslashes() {
    var p = new DorisSqlParser(DorisVersion.DORIS_4_0, new ParserOptions(true, false));
    var d =
        p.parseStatement(
                "CREATE TABLE t (id INT COMMENT 'line\\n"
                    + "next') COMMENT 'tab\\there' PROPERTIES ('path'='a\\\\b')")
            .tableDefinition();
    assertEquals("line\\nnext", d.columns().get(0).comment());
    assertEquals("tab\\there", d.comment());
    assertEquals("a\\\\b", d.properties().get("path"));
  }
}
