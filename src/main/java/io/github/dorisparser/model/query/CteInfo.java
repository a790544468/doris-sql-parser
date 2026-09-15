package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/**
 * One ordered WITH definition. References remain syntactic names rather than physical table
 * bindings.
 */
public record CteInfo(String name, List<String> columnAliases, QueryInfo query, SyntaxNode syntax) {
  public CteInfo {
    columnAliases = List.copyOf(columnAliases);
  }
}
