package io.github.dorisparser.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dorisparser.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** JSON command line. Exit 0: success, 1: usage/I/O error, 2: SQL syntax error. */
public final class Main {
  private static final Set<String> MODES =
      Set.of(
          "parse",
          "multi",
          "split",
          "check",
          "keywords",
          "tree",
          "expression",
          "syntax",
          "expression-syntax");

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  public static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
    ObjectMapper json = new ObjectMapper();
    try {
      String mode = "parse", sql = null, file = null;
      boolean modeSet = false, noEscapes = false, ansi = false;
      DorisVersion version = DorisVersion.DORIS_2_1;
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        switch (arg) {
          case "--help", "-h" -> {
            output.println(help());
            return 0;
          }
          case "--version", "-v" -> {
            version = DorisVersion.fromString(value(args, ++i, arg));
          }
          case "--sql", "-e" -> {
            if (sql != null)
              throw new IllegalArgumentException("SQL input specified more than once");
            sql = value(args, ++i, arg);
          }
          case "--file", "-f" -> {
            if (file != null)
              throw new IllegalArgumentException("File input specified more than once");
            file = value(args, ++i, arg);
          }
          case "--no-backslash-escapes" -> noEscapes = true;
          case "--ansi" -> ansi = true;
          default -> {
            if (MODES.contains(arg)) {
              if (modeSet) throw new IllegalArgumentException("Choose one mode");
              mode = arg;
              modeSet = true;
            } else throw new IllegalArgumentException("Unknown argument: " + arg);
          }
        }
      }
      if (sql != null && file != null)
        throw new IllegalArgumentException("Choose --sql or --file, not both");
      DorisSqlParser parser = new DorisSqlParser(version, new ParserOptions(noEscapes, ansi));
      Object result;
      if (mode.equals("keywords")) {
        if (sql != null || file != null)
          throw new IllegalArgumentException("keywords mode does not accept SQL input");
        result = parser.sqlKeywords();
      } else {
        if (file != null) sql = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        if (sql == null) sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        result =
            switch (mode) {
              case "parse" -> parser.parseStatement(sql);
              case "syntax" -> parser.parseSyntax(sql);
              case "expression-syntax" -> parser.parseExpressionSyntax(sql);
              case "multi" -> parser.parseMultiStatement(sql);
              case "split" -> parser.splitSql(sql);
              case "check" -> {
                parser.checkSqlSyntax(sql);
                yield Map.of(
                    "valid", true, "version", version.id(), "grammarTag", version.sourceTag());
              }
              case "tree" -> Map.of("version", version.id(), "tree", parser.parseTree(sql));
              case "expression" ->
                  Map.of("version", version.id(), "tree", parser.parseExpression(sql));
              default -> throw new IllegalStateException(mode);
            };
      }
      output.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
      return 0;
    } catch (SqlParseException ex) {
      LinkedHashMap<String, Object> diagnostic = new LinkedHashMap<>();
      diagnostic.put("error", "SQL_SYNTAX_ERROR");
      diagnostic.put("version", ex.version().id());
      diagnostic.put("line", ex.line());
      diagnostic.put("column", ex.column());
      diagnostic.put("token", ex.offendingToken());
      diagnostic.put("message", ex.getMessage());
      printError(json, error, diagnostic);
      return 2;
    } catch (IllegalArgumentException | IOException ex) {
      printError(
          json,
          error,
          Map.of("error", "USAGE_OR_IO_ERROR", "message", String.valueOf(ex.getMessage())));
      return 1;
    }
  }

  private static void printError(
      ObjectMapper json, PrintStream error, Map<String, Object> details) {
    try {
      error.println(json.writeValueAsString(details));
    } catch (IOException ex) {
      error.println("Unable to serialize diagnostic: " + ex.getMessage());
    }
  }

  private static String value(String[] args, int i, String option) {
    if (i >= args.length) throw new IllegalArgumentException("Missing value for " + option);
    return args[i];
  }

  private static String help() {
    return """
Doris SQL Parser (Java 17+) — default grammar: 2.1.11-rc01
Usage: java -jar doris-sql-parser-0.2.0-cli.jar [mode] [options]
Modes: parse (default), multi, split, check, keywords, tree, expression, syntax, expression-syntax
Options:
  --version 2.1|4.0       Select pinned version grammar (4.0.8 for 4.0)
  --sql SQL | -e SQL     Parse a command argument
  --file PATH | -f PATH  Read UTF-8 SQL file; otherwise read stdin
  --no-backslash-escapes Enable 4.0 lexer NO_BACKSLASH_ESCAPES
  --ansi                Enable ANSI query organization
  --help                Print this help
Syntax acceptance does not validate tables, columns, types, privileges or execution.
Metadata may be PARTIAL; always inspect metadataStatus and warnings.
""";
  }
}
