package io.github.dorisparser.model.ddl;

import io.github.dorisparser.model.SyntaxNode;
import java.util.*;

public record RollupDefinition(
    String name,
    List<String> columns,
    List<String> duplicateKeys,
    Map<String, String> properties,
    List<PropertyItem> propertyItems,
    SyntaxNode syntax) {
  public RollupDefinition {
    columns = List.copyOf(columns);
    duplicateKeys = List.copyOf(duplicateKeys);
    properties = Map.copyOf(properties);
    propertyItems = List.copyOf(propertyItems);
  }
}
