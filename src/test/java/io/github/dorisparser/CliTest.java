package io.github.dorisparser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dorisparser.cli.Main;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliTest {
  private record Result(int code, String out, String err) {}

  private Result run(String input, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        Main.run(
            args,
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out),
            new PrintStream(err));
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void parseFromStdin() {
    var r = run("select * from sales.orders");
    assertEquals(0, r.code());
    assertTrue(r.out().contains("inputTables"));
    assertTrue(r.out().contains("orders"));
    assertEquals("", r.err());
  }

  @Test
  void checkAndVersion() {
    assertEquals(0, run("SELECT analyzer FROM t", "check").code());
    assertEquals(2, run("SELECT analyzer FROM t", "check", "--version", "4.0").code());
  }

  @Test
  void syntaxDiagnostic() {
    var r = run("SELECT FROM", "check");
    assertEquals(2, r.code());
    assertTrue(r.err().contains("column"));
    assertEquals("", r.out());
  }

  @Test
  void unknownVersionAndConflictingInput() {
    assertEquals(1, run("", "--version", "9").code());
    assertEquals(1, run("", "--sql", "SELECT 1", "--file", "x").code());
  }

  @Test
  void splitAndKeywords() {
    assertTrue(run("SELECT ';';SELECT 2;", "split").out().contains("SELECT 2"));
    assertTrue(run("", "keywords", "--version", "4.0").out().contains("TRY_CAST"));
  }

  @Test
  void explicitSqlAndHelp() {
    assertEquals(0, run("", "--sql", "select 1", "check").code());
    assertTrue(run("", "--help").out().contains("2.1"));
  }

  @Test
  void structuredDocumentAndExpressionModes() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var document = run("-- hi\nSELECT a FROM t; SELECT 2;", "syntax");
    assertEquals(0, document.code());
    var root = mapper.readTree(document.out());
    assertEquals("-- hi\nSELECT a FROM t; SELECT 2;", root.get("source").asText());
    assertTrue(root.get("tokens").isArray());
    var expr = run("a+2", "expression-syntax");
    assertEquals(0, expr.code());
    assertEquals("a+2", mapper.readTree(expr.out()).get("text").asText());
    var metadata = run("INSERT INTO d(a) SELECT s.id FROM s");
    assertEquals(0, metadata.code());
    assertEquals(
        "a",
        mapper
            .readTree(metadata.out())
            .path("insert")
            .path("columnMappings")
            .get(0)
            .path("targetColumn")
            .asText());
  }
}
