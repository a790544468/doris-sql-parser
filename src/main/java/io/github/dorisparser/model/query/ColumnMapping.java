package io.github.dorisparser.model.query;

import java.util.List;

/**
 * Explicit target-column to one-based output ordinal correspondence. Expressions cover VALUES rows
 * or set-operation branches in source order; this is not resolved lineage.
 */
public record ColumnMapping(
    String targetColumn, int sourceOrdinal, List<ExpressionInfo> sourceExpressions) {
  public ColumnMapping {
    sourceExpressions = List.copyOf(sourceExpressions);
  }
}
