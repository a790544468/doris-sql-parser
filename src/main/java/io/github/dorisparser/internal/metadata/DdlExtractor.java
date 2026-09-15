package io.github.dorisparser.internal.metadata;

import static io.github.dorisparser.internal.metadata.Nodes.*;

import io.github.dorisparser.internal.SqlNode;
import io.github.dorisparser.model.ddl.*;
import java.util.*;

/** Grammar-based DDL details. Raw values and syntax are preserved alongside decoded values. */
public final class DdlExtractor {
  private final boolean noBackslashEscapes;

  public DdlExtractor(boolean noBackslashEscapes) {
    this.noBackslashEscapes = noBackslashEscapes;
  }

  private String literal(String text) {
    return stringLiteral(text, noBackslashEscapes);
  }

  public List<PropertyItem> properties(SqlNode node) {
    if (node == null) return List.of();
    return descendants(node, "propertyItem").stream()
        .map(
            n ->
                new PropertyItem(
                    literal(n.fieldText("key")),
                    literal(n.fieldText("value")),
                    n.fieldText("key"),
                    n.fieldText("value"),
                    n.syntax()))
        .toList();
  }

  public Map<String, String> propertyMap(SqlNode node) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var property : properties(node)) result.put(property.key(), property.value());
    return Collections.unmodifiableMap(result);
  }

  public List<IndexDefinition> indexes(SqlNode node) {
    SqlNode defs = node.child("indexDefs");
    if (defs == null) return List.of();
    return defs.children("indexDef").stream()
        .map(
            n ->
                new IndexDefinition(
                    unquote(n.fieldText("indexName")),
                    has(n.directTokens(), "EXISTS"),
                    upper(n.fieldText("indexType")),
                    identifiers(n.field("cols")),
                    literal(n.fieldText("comment")),
                    propertyMap(n.field("properties")),
                    properties(n.field("properties")),
                    n.syntax()))
        .toList();
  }

  public List<RollupDefinition> rollups(SqlNode node) {
    SqlNode defs = node.child("rollupDefs");
    if (defs == null) return List.of();
    return defs.children("rollupDef").stream()
        .map(
            n ->
                new RollupDefinition(
                    unquote(n.fieldText("rollupName")),
                    identifiers(n.field("rollupCols")),
                    identifiers(n.field("dupKeys")),
                    propertyMap(n.field("properties")),
                    properties(n.field("properties")),
                    n.syntax()))
        .toList();
  }

  public PartitionDefinition partition(SqlNode node) {
    if (node == null) return null;
    if (node.rule().equals("mvPartition")) {
      SqlNode key = node.field("partitionKey");
      SqlNode expr = key == null ? node.field("partitionExpr") : key;
      return new PartitionDefinition(
          "MATERIALIZED_VIEW",
          false,
          key == null ? List.of() : List.of(unquote(key.text())),
          expr == null ? List.of() : List.of(expr.syntax()),
          List.of(),
          node.syntax());
    }
    List<String> top = topTokens(node);
    String type = has(top, "RANGE") ? "RANGE" : has(top, "LIST") ? "LIST" : null;
    List<SqlNode> expressions =
        node.field("partitionList") == null
            ? List.of()
            : node.field("partitionList").children("identityOrFunction");
    List<String> columns =
        expressions.stream()
            .filter(n -> n.child("identifier") != null)
            .map(n -> unquote(n.child("identifier").text()))
            .toList();
    List<PartitionItem> items = new ArrayList<>();
    SqlNode defs = node.field("partitions");
    if (defs != null)
      for (SqlNode part : defs.children("partitionDef")) items.add(partitionItem(part));
    return new PartitionDefinition(
        type,
        has(top, "AUTO"),
        columns,
        expressions.stream().map(SqlNode::syntax).toList(),
        items,
        node.syntax());
  }

  private PartitionItem partitionItem(SqlNode wrapper) {
    SqlNode node = wrapper.children().get(0);
    String kind;
    List<PartitionValue> lower = List.of(), upper = List.of();
    List<List<PartitionValue>> in = List.of();
    switch (node.rule()) {
      case "lessThanPartitionDef" -> {
        kind = "LESS_THAN";
        upper =
            node.child("partitionValueList") == null
                ? List.of(new PartitionValue("MAXVALUE", "MAXVALUE", "MAXVALUE"))
                : values(node.child("partitionValueList"));
      }
      case "fixedPartitionDef" -> {
        kind = "FIXED_RANGE";
        lower = values(node.field("lower"));
        upper = values(node.field("upper"));
      }
      case "stepPartitionDef" -> {
        kind = "STEP";
        lower = values(node.field("from"));
        upper = values(node.field("to"));
      }
      case "inPartitionDef" -> {
        kind = "IN";
        // Each tuple is a direct partitionValueList. Single-column IN(a,b) is a list of singleton
        // tuples.
        SqlNode constants = node.field("constants");
        in =
            constants == null
                ? node.children("partitionValueList").stream().map(this::values).toList()
                : values(constants).stream().map(List::of).toList();
      }
      default -> throw new IllegalStateException("Unknown partition alternative " + node.rule());
    }
    SqlNode props = wrapper.field("partitionProperties");
    return new PartitionItem(
        kind,
        unquote(node.fieldText("partitionName")),
        has(node.directTokens(), "EXISTS"),
        lower,
        upper,
        in,
        node.fieldText("unitsAmount"),
        upper(node.fieldText("unit")),
        propertyMap(props),
        properties(props),
        wrapper.syntax());
  }

  private List<PartitionValue> values(SqlNode list) {
    if (list == null) return List.of();
    return list.children("partitionValueDef").stream()
        .map(
            n -> {
              String token = String.join("", n.tokens());
              String kind =
                  token.equalsIgnoreCase("MAXVALUE")
                      ? "MAXVALUE"
                      : token.equalsIgnoreCase("NULL")
                          ? "NULL"
                          : token.startsWith("'") || token.startsWith("\"") ? "STRING" : "INTEGER";
              return new PartitionValue(
                  kind, kind.equals("NULL") ? null : literal(token), n.text());
            })
        .toList();
  }
}
