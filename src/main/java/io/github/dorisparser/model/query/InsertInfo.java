package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import io.github.dorisparser.model.TableId;
import java.util.List;

/**
 * INSERT syntax and only provable explicit ordinal mappings. Empty mappings and reasons represent
 * unresolved target columns, stars, or arity mismatches.
 */
public record InsertInfo(
    TableId targetTable,
    String targetTableId,
    List<String> targetColumns,
    QueryInfo query,
    List<CteInfo> ctes,
    List<ColumnMapping> columnMappings,
    List<String> unresolvedReasons,
    SyntaxNode syntax) {
  public InsertInfo {
    targetColumns = List.copyOf(targetColumns);
    ctes = List.copyOf(ctes);
    columnMappings = List.copyOf(columnMappings);
    unresolvedReasons = List.copyOf(unresolvedReasons);
  }
}
