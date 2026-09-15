package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/** Grouping mode, expressions, and explicit grouping-set boundaries. Empty sets are retained. */
public record GroupByInfo(
    String kind,
    List<ExpressionInfo> expressions,
    List<List<ExpressionInfo>> sets,
    List<OrderItem> orderedExpressions,
    SyntaxNode syntax) {
  public GroupByInfo {
    expressions = List.copyOf(expressions);
    sets = sets.stream().map(List::copyOf).toList();
    orderedExpressions = List.copyOf(orderedExpressions);
  }
}
