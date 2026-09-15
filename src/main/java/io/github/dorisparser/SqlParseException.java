package io.github.dorisparser;

/** Syntax error coordinates are one-based; columns count Unicode code points. */
public final class SqlParseException extends RuntimeException {
  private final DorisVersion version;
  private final String sql;
  private final int line;
  private final int column;
  private final String offendingToken;

  public SqlParseException(
      DorisVersion version,
      String sql,
      int line,
      int column,
      String offendingToken,
      String reason) {
    super("Doris " + version.id() + " at " + line + ":" + column + ": " + reason);
    this.version = version;
    this.sql = sql;
    this.line = line;
    this.column = column;
    this.offendingToken = offendingToken;
  }

  public DorisVersion version() {
    return version;
  }

  public String sql() {
    return sql;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  public String offendingToken() {
    return offendingToken;
  }
}
