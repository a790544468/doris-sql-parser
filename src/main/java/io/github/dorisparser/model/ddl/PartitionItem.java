package io.github.dorisparser.model.ddl;

import io.github.dorisparser.model.SyntaxNode;
import java.util.*;

public record PartitionItem(
    String kind,
    String name,
    boolean ifNotExists,
    List<PartitionValue> lowerBound,
    List<PartitionValue> upperBound,
    List<List<PartitionValue>> inValues,
    String intervalAmount,
    String intervalUnit,
    Map<String, String> properties,
    List<PropertyItem> propertyItems,
    SyntaxNode syntax) {
  public PartitionItem {
    lowerBound = List.copyOf(lowerBound);
    upperBound = List.copyOf(upperBound);
    inValues = inValues.stream().map(List::copyOf).toList();
    properties = Map.copyOf(properties);
    propertyItems = List.copyOf(propertyItems);
  }
}
