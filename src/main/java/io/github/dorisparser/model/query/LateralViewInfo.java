package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/** A lateral view attached to its relation; aliases are syntactic only. */
public record LateralViewInfo(
    String function,
    List<ExpressionInfo> arguments,
    String tableAlias,
    List<String> columnAliases,
    SyntaxNode syntax) {
  public LateralViewInfo {
    arguments = List.copyOf(arguments);
    columnAliases = List.copyOf(columnAliases);
  }
}
