package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/**
 * One JOIN attached to its left relation, including explicit distribution hints and ASOF match
 * condition.
 */
public record JoinInfo(
    String type,
    RelationInfo right,
    ExpressionInfo on,
    List<String> usingColumns,
    List<SyntaxNode> hints,
    ExpressionInfo matchCondition,
    SyntaxNode syntax) {
  public JoinInfo {
    usingColumns = List.copyOf(usingColumns);
    hints = List.copyOf(hints);
  }
}
