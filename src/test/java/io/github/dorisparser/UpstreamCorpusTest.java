package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/**
 * Pinned official SQL corpus, no database execution; metadata smoke checks are not golden lineage
 * tests.
 */
class UpstreamCorpusTest {
  private static String resource(String name) throws Exception {
    try (var in = UpstreamCorpusTest.class.getResourceAsStream("/corpus/" + name)) {
      return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @TestFactory
  Stream<DynamicTest> officialQueries() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (String path : resource("index.txt").lines().toList())
      for (var version : DorisVersion.values()) {
        tests.add(
            DynamicTest.dynamicTest(
                version.id() + "/" + path,
                () -> {
                  var parser = new DorisSqlParser(version);
                  String sql = resource(path);
                  if (Set.of("tpcds/q10.sql", "tpcds/q35.sql", "tpcds/q45.sql").contains(path)) {
                    assertTrue(
                        parser.splitSql(sql).isEmpty(),
                        "Upstream fixture is entirely commented out");
                    assertThrows(SqlParseException.class, () -> parser.checkSqlSyntax(sql));
                    return;
                  }
                  if (path.equals("tpcds/q06.sql")) {
                    var error =
                        assertThrows(SqlParseException.class, () -> parser.checkSqlSyntax(sql));
                    assertEquals(
                        27,
                        error.line(),
                        "Upstream fixture contains an unmatched closing parenthesis");
                    return;
                  }
                  parser.checkSqlSyntax(sql);
                  var result = parser.parseStatement(sql);
                  assertEquals(
                      io.github.dorisparser.model.StatementType.SELECT, result.statementType());
                  assertFalse(result.inputTables().isEmpty());
                  assertTrue(result.outputTables().isEmpty());
                }));
      }
    return tests.stream();
  }
}
