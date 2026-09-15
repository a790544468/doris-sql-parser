package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dorisparser.model.query.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QueryStructureTest {
  private QueryInfo query(DorisVersion version, String sql) {
    return new DorisSqlParser(version).parseStatement(sql).query();
  }

  private InsertInfo insert(DorisVersion version, String sql) {
    return new DorisSqlParser(version).parseStatement(sql).insert();
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void expressionsAliasesAndClausesKeepSyntacticOwnership(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT DISTINCT a.id AS key_id, SUM(b.amount + a.tax) total FROM db.orders a LEFT JOIN"
                + " db.items b ON a.id=b.order_id WHERE a.flag=1 GROUP BY a.id HAVING"
                + " SUM(b.amount)>10 ORDER BY total DESC NULLS LAST LIMIT 5 OFFSET 2");
    assertEquals(QueryInfo.Kind.SELECT, q.kind());
    assertEquals("DISTINCT", q.quantifier());
    assertEquals(
        List.of("key_id", "total"), q.selectItems().stream().map(SelectItem::alias).toList());
    assertEquals("SUM(b.amount + a.tax)", q.selectItems().get(1).expression().text());
    var refs = q.selectItems().get(1).expression().columnReferences();
    assertEquals(List.of("b", "amount"), refs.get(0).parts());
    assertEquals(List.of("b"), refs.get(0).qualifier());
    assertEquals("amount", refs.get(0).column());
    assertEquals(List.of("a", "tax"), refs.get(1).parts());
    assertEquals("a.flag=1", q.where().text());
    assertEquals("a.id", q.groupBy().expressions().get(0).text());
    assertEquals("SUM(b.amount)>10", q.having().text());
    assertEquals("DESC", q.orderBy().get(0).direction());
    assertEquals("LAST", q.orderBy().get(0).nullOrdering());
    assertEquals("5", q.limit().count());
    assertEquals("2", q.limit().offset());
    var relation = q.relations().get(0);
    assertEquals(List.of("db", "orders"), relation.table().parts());
    assertEquals("a", relation.alias());
    assertEquals("LEFT", relation.joins().get(0).type());
    assertEquals("b", relation.joins().get(0).right().alias());
    assertEquals("a.id=b.order_id", relation.joins().get(0).on().text());
    assertNotNull(q.selectItems().get(1).expression().syntax().first("functionCallExpression"));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void nestedSubqueriesDoNotLeakColumnsOrClauses(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT a.id, (SELECT max(x.v) FROM inner_t x WHERE x.id=a.id LIMIT 1) value FROM"
                + " outer_t a WHERE EXISTS (SELECT 1 FROM other_t z WHERE z.id=a.id) LIMIT 7");
    var e = q.selectItems().get(1).expression();
    assertTrue(e.columnReferences().isEmpty());
    assertEquals(1, e.subqueries().size());
    assertEquals(
        "x",
        e.subqueries()
            .get(0)
            .selectItems()
            .get(0)
            .expression()
            .columnReferences()
            .get(0)
            .qualifier()
            .get(0));
    assertEquals("1", e.subqueries().get(0).limit().count());
    assertEquals("7", q.limit().count());
    assertTrue(q.where().columnReferences().isEmpty());
    assertEquals(1, q.where().subqueries().size());
    assertEquals(1, q.relations().size());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void ctesDerivedTablesAndSetOperationsKeepSeparateScopes(DorisVersion version) {
    var q =
        query(
            version,
            "WITH c(k) AS (SELECT id FROM base_t), d AS (SELECT k FROM c) SELECT x.k FROM (SELECT k"
                + " FROM d) x UNION ALL SELECT id FROM other_t LIMIT 3");
    assertEquals(2, q.ctes().size());
    assertEquals("c", q.ctes().get(0).name());
    assertEquals(List.of("k"), q.ctes().get(0).columnAliases());
    assertEquals("id", q.ctes().get(0).query().selectItems().get(0).expression().text());
    assertEquals(QueryInfo.Kind.SET_OPERATION, q.kind());
    assertEquals("UNION", q.setOperation().operator());
    assertEquals("ALL", q.setOperation().quantifier());
    assertTrue(q.selectItems().isEmpty());
    assertNull(q.limit());
    var left = q.setOperation().left();
    assertEquals("x", left.relations().get(0).alias());
    assertEquals("k", left.relations().get(0).subquery().selectItems().get(0).expression().text());
    assertEquals("3", q.setOperation().right().limit().count());
  }

  @Test
  void ansiOrganizationBelongsToUnionRatherThanRightBranch() {
    var q =
        new DorisSqlParser(DorisVersion.DORIS_4_0, new ParserOptions(false, true))
            .parseStatement("SELECT id FROM a UNION ALL SELECT id FROM b ORDER BY id LIMIT 9")
            .query();
    assertEquals("9", q.limit().count());
    assertEquals(1, q.orderBy().size());
    assertNull(q.setOperation().right().limit());
    assertTrue(q.setOperation().right().orderBy().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void starsAndQuotedIdentifiersAreNotPhysicalColumnResolution(DorisVersion version) {
    var q = query(version, "SELECT a.*, `a.b`.`c.d`, COUNT(*) n, a.arr[1].name FROM t a");
    assertTrue(q.selectItems().get(0).wildcard());
    assertEquals(
        List.of("a"), q.selectItems().get(0).expression().columnReferences().get(0).qualifier());
    assertEquals(
        List.of("a.b", "c.d"),
        q.selectItems().get(1).expression().columnReferences().get(0).parts());
    assertFalse(q.selectItems().get(2).wildcard());
    assertFalse(q.selectItems().get(3).wildcard());
    assertEquals(
        List.of("a", "arr"), q.selectItems().get(3).expression().columnReferences().get(0).parts());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void usingHintsLateralAndParenthesizedRelationsStayAttached(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT a.id FROM (t a JOIN [broadcast] u b USING (id)) JOIN v c ON b.id=c.id");
    var grouped = q.relations().get(0);
    assertEquals(RelationInfo.Kind.GROUP, grouped.kind());
    assertEquals(1, grouped.members().size());
    var inner = grouped.members().get(0).joins().get(0);
    assertEquals(List.of("id"), inner.usingColumns());
    assertTrue(inner.hints().get(0).text().contains("broadcast"));
    assertEquals("c", grouped.joins().get(0).right().alias());
    var lateral =
        query(version, "SELECT e FROM t LATERAL VIEW explode(arr) lv AS e")
            .relations()
            .get(0)
            .lateralViews()
            .get(0);
    assertEquals("explode", lateral.function());
    assertEquals("lv", lateral.tableAlias());
    assertEquals(List.of("e"), lateral.columnAliases());
    assertEquals("arr", lateral.arguments().get(0).text());
  }

  @Test
  void qualifyAndGroupingSetsRemainStructured() {
    var q =
        query(
            DorisVersion.DORIS_4_0,
            "SELECT a, sum(b) s FROM t GROUP BY GROUPING SETS ((a), ()) HAVING sum(b)>0 QUALIFY"
                + " row_number() OVER (ORDER BY a)=1");
    assertEquals("GROUPING SETS", q.groupBy().kind());
    assertEquals(2, q.groupBy().sets().size());
    assertEquals("a", q.groupBy().sets().get(0).get(0).text());
    assertTrue(q.groupBy().sets().get(1).isEmpty());
    assertEquals("row_number() OVER (ORDER BY a)=1", q.qualify().text());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void valuesAndExplicitInsertMappingsArePositional(DorisVersion version) {
    var i = insert(version, "INSERT INTO db.dst (id, amount) VALUES (1, 2), (3, DEFAULT)");
    assertEquals(List.of("db", "dst"), i.targetTable().parts());
    assertEquals(List.of("id", "amount"), i.targetColumns());
    assertEquals(QueryInfo.Kind.VALUES, i.query().kind());
    assertEquals("DEFAULT", i.query().valuesRows().get(1).get(1).text());
    assertTrue(i.unresolvedReasons().isEmpty());
    assertEquals(2, i.columnMappings().size());
    assertEquals(2, i.columnMappings().get(1).sourceOrdinal());
    assertEquals("amount", i.columnMappings().get(1).targetColumn());
    assertEquals(
        List.of("2", "DEFAULT"),
        i.columnMappings().get(1).sourceExpressions().stream().map(ExpressionInfo::text).toList());
    var select = insert(version, "INSERT INTO dst (id, amount) SELECT s.id, s.price*2 FROM src s");
    assertEquals("s.price*2", select.columnMappings().get(1).sourceExpressions().get(0).text());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void insertRefusesInventedColumnsWildcardExpansionOrUnsafeArity(DorisVersion version) {
    for (String sql :
        List.of(
            "INSERT INTO dst SELECT id FROM src",
            "INSERT INTO dst(id) SELECT * FROM src",
            "INSERT INTO dst(id) SELECT a,b FROM src",
            "INSERT INTO dst(id) VALUES (1), (2,3)",
            "INSERT INTO dst(id) SELECT a FROM t UNION ALL SELECT b,c FROM u")) {
      var i = insert(version, sql);
      assertTrue(i.columnMappings().isEmpty(), sql);
      assertFalse(i.unresolvedReasons().isEmpty(), sql);
    }
    var count = insert(version, "INSERT INTO dst(n) SELECT COUNT(*) FROM src");
    assertEquals(1, count.columnMappings().size());
    var union = insert(version, "INSERT INTO dst(id) SELECT a FROM t UNION ALL SELECT b FROM u");
    assertEquals(
        List.of("a", "b"),
        union.columnMappings().get(0).sourceExpressions().stream()
            .map(ExpressionInfo::text)
            .toList());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void outerInsertCteAndScalarValuesQueriesAreNotLost(DorisVersion version) {
    var i = insert(version, "WITH c AS (SELECT id FROM src) INSERT INTO dst(id) SELECT id FROM c");
    assertEquals("c", i.ctes().get(0).name());
    var scalar = insert(version, "INSERT INTO dst(id) VALUES ((SELECT id FROM src))");
    assertEquals(QueryInfo.Kind.VALUES, scalar.query().kind());
    assertEquals(1, scalar.columnMappings().get(0).sourceExpressions().get(0).subqueries().size());
    assertNotNull(i.syntax());
    assertThrows(UnsupportedOperationException.class, () -> i.targetColumns().add("no"));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void excludedProjectionStarsAndValuesStarsStayUnresolved(DorisVersion version) {
    var q = query(version, "SELECT * EXCEPT (secret) FROM t");
    assertEquals(1, q.selectItems().size());
    assertTrue(q.selectItems().get(0).wildcard());
    assertTrue(
        q.selectItems().get(0).expression().columnReferences().stream()
            .anyMatch(ColumnReference::wildcard));
    assertTrue(
        insert(version, "INSERT INTO dst(id) SELECT * EXCEPT (secret) FROM t")
            .columnMappings()
            .isEmpty());
    var values = insert(version, "INSERT INTO dst(id) VALUES (*)");
    assertTrue(values.columnMappings().isEmpty());
    assertFalse(values.unresolvedReasons().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void parenthesizedSetBranchesAndPrecedenceStayVisible(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT a FROM t UNION ALL (SELECT b FROM u LIMIT 2) INTERSECT SELECT c FROM v");
    assertEquals("UNION", q.setOperation().operator());
    assertEquals("INTERSECT", q.setOperation().right().setOperation().operator());
    var parenthesized = q.setOperation().right().setOperation().left();
    assertEquals(QueryInfo.Kind.PARENTHESIZED, parenthesized.kind());
    assertEquals("2", parenthesized.nestedQuery().limit().count());
    assertNull(q.limit());
    var i = insert(version, "INSERT INTO dst(a) (SELECT b FROM t)");
    assertEquals("b", i.columnMappings().get(0).sourceExpressions().get(0).text());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void lambdaParametersAreLocalAndWindowReferencesAreRetained(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT array_map(x -> x + threshold, arr), SUM(v) OVER (PARTITION BY k ORDER BY ts"
                + " ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) FROM t");
    assertEquals(
        List.of("threshold", "arr"),
        q.selectItems().get(0).expression().columnReferences().stream()
            .map(ColumnReference::column)
            .toList());
    assertEquals(
        List.of("v", "k", "ts"),
        q.selectItems().get(1).expression().columnReferences().stream()
            .map(ColumnReference::column)
            .toList());
    assertNotNull(q.selectItems().get(1).expression().syntax().first("windowSpec"));
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void tableFunctionsRelationHintsAndHugeLimitsDoNotLoseSyntax(DorisVersion version) {
    var q =
        query(
            version,
            "SELECT n.number FROM numbers('number'='3') n JOIN t [shuffle] ON n.number=t.id LIMIT"
                + " 9223372036854775808");
    assertEquals(RelationInfo.Kind.TABLE_FUNCTION, q.relations().get(0).kind());
    assertEquals("numbers", q.relations().get(0).function());
    assertEquals("n", q.relations().get(0).alias());
    assertEquals("[shuffle]", q.relations().get(0).joins().get(0).right().hints().get(0).text());
    assertEquals("9223372036854775808", q.limit().count());
    var internal = insert(version, "INSERT INTO DORIS_INTERNAL_TABLE_ID(123) (id) VALUES (1)");
    assertNull(internal.targetTable());
    assertEquals("123", internal.targetTableId());
    assertEquals(1, internal.columnMappings().size());
  }

  @Test
  void stringAliasesRespectNoBackslashEscapes() {
    String sql = "SELECT 1 AS 'line\\nnext'";
    assertEquals(
        "line\nnext",
        new DorisSqlParser(DorisVersion.DORIS_4_0)
            .parseStatement(sql)
            .query()
            .selectItems()
            .get(0)
            .alias());
    assertEquals(
        "line\\nnext",
        new DorisSqlParser(DorisVersion.DORIS_4_0, new ParserOptions(true, false))
            .parseStatement(sql)
            .query()
            .selectItems()
            .get(0)
            .alias());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void parenthesizedProjectionStarsCannotProveInsertWidth(DorisVersion version) {
    for (String projection : List.of("(*)", "((t.*))")) {
      var q = query(version, "SELECT " + projection + " FROM t");
      assertTrue(q.selectItems().get(0).wildcard(), projection);
      var i = insert(version, "INSERT INTO dst(a) SELECT " + projection + " FROM t");
      assertTrue(i.columnMappings().isEmpty(), projection);
      assertFalse(i.unresolvedReasons().isEmpty(), projection);
    }
    for (String sql :
        List.of(
            "INSERT INTO dst(a) VALUES ((*))",
            "INSERT INTO dst(a) VALUES (((t.*)))",
            "INSERT INTO dst(a) SELECT a FROM t UNION ALL SELECT ((u.*)) FROM u")) {
      var i = insert(version, sql);
      assertTrue(i.columnMappings().isEmpty(), sql);
      assertFalse(i.unresolvedReasons().isEmpty(), sql);
    }
    for (String expression : List.of("COUNT(*)", "(COUNT(*))", "a*2", "((a*2))")) {
      var i = insert(version, "INSERT INTO dst(a) SELECT " + expression + " FROM t");
      assertFalse(i.query().selectItems().get(0).wildcard(), expression);
      assertEquals(1, i.columnMappings().size(), expression);
      assertTrue(i.unresolvedReasons().isEmpty(), expression);
    }
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void lambdaNamesMatchCaseInsensitivelyWithoutChangingExternalSpelling(DorisVersion version) {
    for (String expression :
        List.of(
            "array_map(x -> X+MiXeD, Arr)",
            "array_map(`x` -> `X`+MiXeD, Arr)",
            "array_map(I -> i+MiXeD, Arr)")) {
      var q = query(version, "SELECT " + expression + ", X FROM t");
      assertEquals(
          List.of("MiXeD", "Arr"),
          q.selectItems().get(0).expression().columnReferences().stream()
              .map(ColumnReference::column)
              .toList(),
          expression);
      assertEquals(
          List.of("X"),
          q.selectItems().get(1).expression().columnReferences().stream()
              .map(ColumnReference::column)
              .toList(),
          expression);
    }
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void nestedLambdaShadowingRetainsOuterBindingsAndExternalReferences(DorisVersion version) {
    for (String expression :
        List.of(
            "array_map(x -> array_map(X -> x+MiXeD, inner_arr)+X, outer_arr)",
            "array_map(`x` -> array_map(y -> X+Y+MiXeD, inner_arr)+`X`, outer_arr)")) {
      var q = query(version, "SELECT " + expression + " FROM t");
      assertEquals(
          List.of("MiXeD", "inner_arr", "outer_arr"),
          q.selectItems().get(0).expression().columnReferences().stream()
              .map(ColumnReference::column)
              .toList(),
          expression);
    }
  }
}
