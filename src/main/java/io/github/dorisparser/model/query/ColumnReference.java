package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import java.util.List;

/**
 * A syntactic name chain, not schema binding. Qualifiers may be aliases or struct paths; no
 * physical table ownership is inferred.
 */
public record ColumnReference(List<String> parts, boolean wildcard, SyntaxNode syntax) {
  public ColumnReference {
    parts = List.copyOf(parts);
  }

  public List<String> qualifier() {
    return parts.subList(0, Math.max(0, parts.size() - 1));
  }

  public String column() {
    return parts.isEmpty() ? null : parts.get(parts.size() - 1);
  }
}
