package io.github.dorisparser.model;

import io.github.dorisparser.model.ddl.*;
import java.util.List;
import java.util.Map;

public record TableDefinition(
    TableId table,
    List<ColumnDefinition> columns,
    boolean ifNotExists,
    boolean external,
    boolean temporary,
    String engine,
    String keyType,
    List<String> keyColumns,
    String comment,
    String partitionSql,
    String distributionType,
    List<String> distributionColumns,
    String buckets,
    Map<String, String> properties,
    String querySql,
    PartitionDefinition partition,
    DistributionDefinition distribution,
    List<String> clusterKeys,
    List<IndexDefinition> indexes,
    List<RollupDefinition> rollups,
    List<PropertyItem> propertyItems,
    Map<String, String> externalProperties,
    List<PropertyItem> externalPropertyItems,
    List<String> ctasColumns,
    SyntaxNode syntax) {
  public TableDefinition {
    clusterKeys = List.copyOf(clusterKeys);
    indexes = List.copyOf(indexes);
    rollups = List.copyOf(rollups);
    propertyItems = List.copyOf(propertyItems);
    externalProperties = Map.copyOf(externalProperties);
    externalPropertyItems = List.copyOf(externalPropertyItems);
    ctasColumns = List.copyOf(ctasColumns);
    columns = List.copyOf(columns);
    keyColumns = List.copyOf(keyColumns);
    distributionColumns = List.copyOf(distributionColumns);
    properties = Map.copyOf(properties);
  }

  /** Declared CLUSTER BY columns when present, otherwise the declared key columns. */
  public List<String> sortColumns() {
    return clusterKeys.isEmpty() ? keyColumns : clusterKeys;
  }

  /** Compatibility constructor for the 0.1.x metadata shape. */
  public TableDefinition(
      TableId table,
      List<ColumnDefinition> columns,
      boolean ifNotExists,
      boolean external,
      boolean temporary,
      String engine,
      String keyType,
      List<String> keyColumns,
      String comment,
      String partitionSql,
      String distributionType,
      List<String> distributionColumns,
      String buckets,
      Map<String, String> properties,
      String querySql) {
    this(
        table,
        columns,
        ifNotExists,
        external,
        temporary,
        engine,
        keyType,
        keyColumns,
        comment,
        partitionSql,
        distributionType,
        distributionColumns,
        buckets,
        properties,
        querySql,
        null,
        distributionType == null
            ? null
            : new DistributionDefinition(distributionType, distributionColumns, buckets),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        List.of(),
        List.of(),
        null);
  }
}
