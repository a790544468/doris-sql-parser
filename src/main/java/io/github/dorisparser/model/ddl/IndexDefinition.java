package io.github.dorisparser.model.ddl;

import io.github.dorisparser.model.SyntaxNode;
import java.util.*;

public record IndexDefinition(
    String name,
    boolean ifNotExists,
    String type,
    List<String> columns,
    String comment,
    Map<String, String> properties,
    List<PropertyItem> propertyItems,
    SyntaxNode syntax) {
  public IndexDefinition {
    columns = List.copyOf(columns);
    properties = Map.copyOf(properties);
    propertyItems = List.copyOf(propertyItems);
  }
}
