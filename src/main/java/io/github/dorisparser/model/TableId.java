package io.github.dorisparser.model;

import java.util.List;
import java.util.Objects;

/** Identifier segments retain case and quoted dots; absent catalog/database remain null. */
public record TableId(List<String> parts) {
  public TableId {
    parts = List.copyOf(parts);
    if (parts.isEmpty()) throw new IllegalArgumentException("Empty table name");
    parts.forEach(Objects::requireNonNull);
  }

  public String tableName() {
    return parts.get(parts.size() - 1);
  }

  public String databaseName() {
    return parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
  }

  public String catalogName() {
    return parts.size() >= 3 ? String.join(".", parts.subList(0, parts.size() - 2)) : null;
  }

  public String qualifiedName() {
    return parts.stream()
        .map(s -> "`" + s.replace("`", "``") + "`")
        .collect(java.util.stream.Collectors.joining("."));
  }
}
