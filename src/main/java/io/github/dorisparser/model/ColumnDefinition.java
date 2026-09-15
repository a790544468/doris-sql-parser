package io.github.dorisparser.model;

public record ColumnDefinition(
    String name,
    String dataType,
    Boolean nullable,
    String defaultExpression,
    String comment,
    String aggregateType,
    boolean key,
    boolean autoIncrement,
    String generatedExpression,
    String autoIncrementStart,
    String onUpdateExpression,
    SyntaxNode typeSyntax,
    SyntaxNode syntax) {
  /** Compatibility constructor for the 0.1.x metadata shape. */
  public ColumnDefinition(
      String name,
      String dataType,
      Boolean nullable,
      String defaultExpression,
      String comment,
      String aggregateType,
      boolean key,
      boolean autoIncrement,
      String generatedExpression) {
    this(
        name,
        dataType,
        nullable,
        defaultExpression,
        comment,
        aggregateType,
        key,
        autoIncrement,
        generatedExpression,
        null,
        null,
        null,
        null);
  }
}
