package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/**
 * An expression with its complete syntax and references from this query scope only. Nested queries
 * have their own metadata.
 */
public record ExpressionInfo(
    SyntaxNode syntax, List<ColumnReference> columnReferences, List<QueryInfo> subqueries) {
  public ExpressionInfo {
    columnReferences = List.copyOf(columnReferences);
    subqueries = List.copyOf(subqueries);
  }

  public String text() {
    return syntax.text();
  }
}
