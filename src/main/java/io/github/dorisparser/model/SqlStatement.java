package io.github.dorisparser.model;

import io.github.dorisparser.DorisVersion;
import io.github.dorisparser.model.query.InsertInfo;
import io.github.dorisparser.model.query.QueryInfo;
import java.util.List;
import java.util.Map;

public record SqlStatement(
    StatementType statementType,
    DorisVersion version,
    String sql,
    List<TableId> inputTables,
    List<TableId> outputTables,
    List<String> functionNames,
    Long limit,
    Long offset,
    boolean explained,
    TableDefinition tableDefinition,
    List<AlterAction> alterActions,
    Map<String, String> attributes,
    MetadataStatus metadataStatus,
    List<String> warnings,
    SyntaxNode syntax,
    QueryInfo query,
    InsertInfo insert) {
  public SqlStatement {
    inputTables = List.copyOf(inputTables);
    outputTables = List.copyOf(outputTables);
    functionNames = List.copyOf(functionNames);
    alterActions = List.copyOf(alterActions);
    attributes = Map.copyOf(attributes);
    warnings = List.copyOf(warnings);
  }

  /** Compatibility constructor for the 0.1.x metadata shape. */
  public SqlStatement(
      StatementType statementType,
      DorisVersion version,
      String sql,
      List<TableId> inputTables,
      List<TableId> outputTables,
      List<String> functionNames,
      Long limit,
      Long offset,
      boolean explained,
      TableDefinition tableDefinition,
      List<AlterAction> alterActions,
      Map<String, String> attributes,
      MetadataStatus metadataStatus,
      List<String> warnings) {
    this(
        statementType,
        version,
        sql,
        inputTables,
        outputTables,
        functionNames,
        limit,
        offset,
        explained,
        tableDefinition,
        alterActions,
        attributes,
        metadataStatus,
        warnings,
        null,
        null,
        null);
  }
}
