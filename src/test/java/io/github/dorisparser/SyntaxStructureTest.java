package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SyntaxStructureTest {
  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void retainsCompleteDocumentAndGlobalUnicodeSpans(DorisVersion version) {
    String sql =
        "-- leading 😀\n; SELECT '😀' AS `名`, /* keep */ t.id FROM db.t t;\nSELECT 2; -- end\n";
    var parser = new DorisSqlParser(version);
    var document = parser.parseSyntax(sql);
    assertEquals(sql, document.source());
    assertEquals(sql, document.tokens().stream().map(t -> t.text()).reduce("", String::concat));
    var column = document.root().descendants("namedExpression").get(1);
    assertEquals("t.id", column.text());
    assertEquals(column.text(), column.span().slice(sql));
    assertEquals(2, column.span().line());
    assertEquals(3, document.root().descendants("namedExpression").size());
    var second = parser.parseMultiStatement(sql).get(1).syntax();
    assertEquals("SELECT 2", second.text());
    assertEquals("SELECT 2", second.span().slice(sql));
    assertTrue(document.tokens().stream().anyMatch(t -> t.hidden() && t.text().contains("keep")));
    assertThrows(UnsupportedOperationException.class, () -> column.children().clear());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void exposesRuleLabelsTerminalsAndUnknownCommandParts(DorisVersion version) {
    var result =
        new DorisSqlParser(version)
            .parseStatement(
                "CREATE TABLE db.t (id INT COMMENT '标识') DISTRIBUTED BY HASH(id) BUCKETS 3");
    var create = result.syntax().descendants("supportedCreateStatement").get(0);
    assertEquals("db.t", create.field("name").text());
    var col = create.first("columnDef");
    assertEquals("id", col.field("colName").text());
    assertEquals("'标识'", col.field("comment").text());
    assertEquals("INT", col.field("type").text());
    assertNull(col.field("missing"));
    assertEquals(List.of(), col.fields("missing"));
    assertTrue(create.children().stream().anyMatch(n -> n.kind().equals("BUCKETS")));
    assertEquals(
        "'x'",
        new DorisSqlParser(version)
            .parseStatement("SET a = 'x'")
            .syntax()
            .first("constant")
            .text());
  }

  @ParameterizedTest
  @EnumSource(DorisVersion.class)
  void emptyDocumentAndExpressionRemainNavigable(DorisVersion version) {
    var parser = new DorisSqlParser(version);
    assertEquals(" -- empty", parser.parseSyntax(" -- empty").source());
    var e = parser.parseExpressionSyntax("a + /*c*/ b * 2");
    assertEquals("a + /*c*/ b * 2", e.text());
    assertFalse(e.descendants("primaryExpression").isEmpty());
    assertThrows(SqlParseException.class, () -> parser.parseExpressionSyntax("a +"));
    assertEquals("SELECT 1 FROM DUAL", parser.parseStatement("SELECT 1 FROM DUAL").syntax().text());
  }
}
