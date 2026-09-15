package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/**
 * Scoped query metadata. Set operations and parenthesized queries retain child queries; no nested
 * clause or reference is flattened into its parent.
 */
public record QueryInfo(
    Kind kind,
    String quantifier,
    List<SelectItem> selectItems,
    List<RelationInfo> relations,
    ExpressionInfo where,
    GroupByInfo groupBy,
    ExpressionInfo having,
    ExpressionInfo qualify,
    List<OrderItem> orderBy,
    LimitInfo limit,
    List<CteInfo> ctes,
    SetOperationInfo setOperation,
    QueryInfo nestedQuery,
    List<List<ExpressionInfo>> valuesRows,
    List<SyntaxNode> hints,
    SyntaxNode syntax,
    List<String> warnings) {
  public QueryInfo {
    selectItems = List.copyOf(selectItems);
    relations = List.copyOf(relations);
    orderBy = List.copyOf(orderBy);
    ctes = List.copyOf(ctes);
    valuesRows = valuesRows.stream().map(List::copyOf).toList();
    hints = List.copyOf(hints);
    warnings = List.copyOf(warnings);
  }

  public enum Kind {
    SELECT,
    SET_OPERATION,
    VALUES,
    PARENTHESIZED,
    OTHER
  }
}
