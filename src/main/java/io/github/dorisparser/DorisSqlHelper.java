package io.github.dorisparser;

import io.github.dorisparser.model.SqlStatement;
import java.util.List;

/** Convenience static API inspired by superior-sql-parser; defaults to Doris 2.1. */
public final class DorisSqlHelper {
  private static final DorisSqlParser DEFAULT = new DorisSqlParser();

  private DorisSqlHelper() {}

  public static SqlStatement parseStatement(String sql) {
    return DEFAULT.parseStatement(sql);
  }

  public static SqlStatement parseStatement(String sql, DorisVersion version) {
    return new DorisSqlParser(version).parseStatement(sql);
  }

  public static List<SqlStatement> parseMultiStatement(String sql) {
    return DEFAULT.parseMultiStatement(sql);
  }

  public static List<SqlStatement> parseMultiStatement(String sql, DorisVersion version) {
    return new DorisSqlParser(version).parseMultiStatement(sql);
  }

  public static List<String> splitSql(String sql) {
    return DEFAULT.splitSql(sql);
  }

  public static List<String> splitSql(String sql, DorisVersion version) {
    return new DorisSqlParser(version).splitSql(sql);
  }

  public static void checkSqlSyntax(String sql) {
    DEFAULT.checkSqlSyntax(sql);
  }

  public static void checkSqlSyntax(String sql, DorisVersion version) {
    new DorisSqlParser(version).checkSqlSyntax(sql);
  }

  public static List<String> sqlKeywords() {
    return DEFAULT.sqlKeywords();
  }

  public static List<String> sqlKeywords(DorisVersion version) {
    return new DorisSqlParser(version).sqlKeywords();
  }
}
