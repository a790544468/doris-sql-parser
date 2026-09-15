package io.github.dorisparser.model.ddl;

import io.github.dorisparser.model.SyntaxNode;
import java.util.*;

public record PartitionDefinition(
    String type,
    boolean automatic,
    List<String> columns,
    List<SyntaxNode> expressions,
    List<PartitionItem> partitions,
    SyntaxNode syntax) {
  public PartitionDefinition {
    columns = List.copyOf(columns);
    expressions = List.copyOf(expressions);
    partitions = List.copyOf(partitions);
  }
}
