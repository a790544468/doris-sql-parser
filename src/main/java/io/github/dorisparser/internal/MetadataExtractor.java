package io.github.dorisparser.internal;

import static io.github.dorisparser.internal.metadata.Nodes.*;

import io.github.dorisparser.DorisVersion;
import io.github.dorisparser.internal.metadata.DdlExtractor;
import io.github.dorisparser.internal.metadata.QueryExtractor;
import io.github.dorisparser.model.*;
import io.github.dorisparser.model.ddl.*;
import io.github.dorisparser.model.query.*;
import java.util.*;

/** Version-neutral metadata derived exclusively from parsed grammar nodes and tokens. */
public final class MetadataExtractor {
  private MetadataExtractor() {}

  public static SqlStatement extract(SqlNode statement, DorisVersion version) {
    return extract(statement, version, false);
  }

  public static SqlStatement extract(
      SqlNode statement, DorisVersion version, boolean noBackslashEscapes) {
    return new Extraction(statement, version, noBackslashEscapes).run();
  }

  private static final class Extraction {
    final SqlNode original, root;
    final DorisVersion version;
    final boolean noBackslashEscapes;
    final LinkedHashSet<TableId> reads = new LinkedHashSet<>(), writes = new LinkedHashSet<>();
    final LinkedHashSet<String> functions = new LinkedHashSet<>();
    final List<String> warnings = new ArrayList<>();
    final List<AlterAction> actions = new ArrayList<>();
    final Map<String, String> attributes = new LinkedHashMap<>();
    Long limit, offset;

    Extraction(SqlNode original, DorisVersion version, boolean noBackslashEscapes) {
      this.original = original;
      this.version = version;
      this.noBackslashEscapes = noBackslashEscapes;
      this.root = unwrap(original);
    }

    SqlStatement run() {
      StatementType type = classify(root);
      boolean explained = root.child("explain") != null;
      attributes.put("grammarRule", root.rule());
      attributes.put("grammarKind", root.kind());
      TableDefinition definition = null;
      switch (type) {
        case SELECT -> walk(root, Set.of());
        case INSERT, INSERT_SELECT, INSERT_OVERWRITE -> {
          add(writes, table(root.field("tableName")));
          walk(root, Set.of());
          if (writes.isEmpty())
            warn("Insert target uses an internal table ID; no qualified table name is available.");
        }
        case UPDATE, DELETE -> {
          TableId target = table(root.field("tableName"));
          add(reads, target);
          add(writes, target);
          walk(root, Set.of());
        }
        case MERGE -> {
          TableId target = table(root.field("targetTable"));
          add(reads, target);
          add(writes, target);
          walk(root, Set.of());
        }
        case CREATE_TABLE,
            CREATE_TABLE_AS_SELECT,
            CREATE_TABLE_LIKE,
            CREATE_VIEW,
            CREATE_MATERIALIZED_VIEW -> {
          add(writes, table(field(root, "name", "mvName")));
          if (type == StatementType.CREATE_TABLE_LIKE)
            add(reads, table(root.field("existedTable")));
          walk(root, Set.of());
          definition = definition(root);
        }
        case ALTER_TABLE -> {
          add(writes, table(field(root, "tableName", "name", "table")));
          for (SqlNode child : root.children())
            if (Set.of("alterTableClause", "addRollupClause", "dropRollupClause", "constraint")
                .contains(child.rule())) actions.add(new AlterAction(child.kind(), child.text()));
          if (actions.isEmpty()) actions.add(new AlterAction(root.kind(), root.text()));
          walk(root, Set.of());
        }
        case ALTER_VIEW -> {
          add(writes, table(root.field("name")));
          walk(root, Set.of());
        }
        case DROP_TABLE, DROP_VIEW, DROP_MATERIALIZED_VIEW, TRUNCATE_TABLE -> {
          SqlNode target = field(root, "name", "mvName");
          if (target == null) target = root.child("multipartIdentifier");
          add(writes, table(target));
        }
        case EXPORT -> {
          add(reads, table(root.field("tableName")));
          walk(root, Set.of());
          warn("Export destination and options are not normalized.");
        }
        case DESCRIBE, ANALYZE -> {
          add(reads, table(field(root, "tableName", "name")));
          if (reads.isEmpty()) add(reads, table(root.child("multipartIdentifier")));
          warn("Only command classification and explicitly named table metadata are extracted.");
        }
        case COPY -> {
          add(writes, table(root.field("name")));
          warn("COPY stage sources and options are not normalized.");
        }
        case LOAD -> {
          for (SqlNode d : descendants(root, "dataDesc")) {
            add(writes, table(d.field("targetTableName")));
            add(reads, table(d.field("sourceTableName")));
          }
          warn("LOAD file sources, transformations and options are not normalized.");
        }
        default -> warn("Metadata extraction is partial for grammar command " + root.kind() + ".");
      }
      if (Set.of(
              StatementType.SELECT,
              StatementType.INSERT,
              StatementType.INSERT_SELECT,
              StatementType.INSERT_OVERWRITE,
              StatementType.UPDATE,
              StatementType.DELETE,
              StatementType.CREATE_TABLE_AS_SELECT,
              StatementType.CREATE_VIEW,
              StatementType.CREATE_MATERIALIZED_VIEW)
          .contains(type)) {
        SqlNode query = root.child("query");
        findLimit(query == null ? root : query);
      }
      if (explained) writes.clear();
      InsertInfo insert = QueryExtractor.insert(root, noBackslashEscapes);
      QueryInfo query =
          insert == null ? QueryExtractor.extract(root, noBackslashEscapes) : insert.query();
      return new SqlStatement(
          type,
          version,
          original.text(),
          new ArrayList<>(reads),
          new ArrayList<>(writes),
          new ArrayList<>(functions),
          limit,
          offset,
          explained,
          definition,
          actions,
          attributes,
          warnings.isEmpty() ? MetadataStatus.COMPLETE : MetadataStatus.PARTIAL,
          warnings,
          original.syntax(),
          query,
          insert);
    }

    String literal(String text) {
      return stringLiteral(text, noBackslashEscapes);
    }

    void warn(String text) {
      if (!warnings.contains(text)) warnings.add(text);
    }

    void add(Set<TableId> to, TableId table) {
      if (table != null) to.add(table);
    }

    /** CTE bodies see preceding siblings plus outer bindings; not later siblings or themselves. */
    void walk(SqlNode node, Set<String> outer) {
      Set<String> scope = outer;
      SqlNode cte = node.child("cte");
      if (cte != null) {
        scope = new HashSet<>(outer);
        for (SqlNode alias : cte.children("aliasQuery")) {
          walk(alias.child("query"), scope);
          scope.add(unquote(alias.child("identifier").text()).toLowerCase(Locale.ROOT));
        }
      }
      if (node.kind().equals("TableName") && node.rule().equals("relationPrimary")) {
        TableId id = table(node.child("multipartIdentifier"));
        if (id != null
            && (id.parts().size() > 1 || !scope.contains(id.tableName().toLowerCase(Locale.ROOT))))
          reads.add(id);
      }
      if (node.rule().equals("functionCallExpression")) {
        SqlNode id = node.child("functionIdentifier");
        if (id != null) functions.add(upper(String.join("", id.tokens())));
      }
      if (node.kind().equals("TableValuedFunction")) {
        functions.add(upper(unquote(node.fieldText("tvfName"))));
        warn(
            "Table-valued function sources are represented as functions; external physical sources"
                + " are not resolved.");
      }
      if (node.rule().equals("lateralView"))
        functions.add(upper(unquote(node.fieldText("functionName"))));
      if (node.rule().equals("primaryExpression")
          && Set.of(
                  "Cast",
                  "TryCast",
                  "CharFunction",
                  "ConvertCharSet",
                  "ConvertType",
                  "GroupConcat",
                  "GetFormatFunction",
                  "Trim",
                  "Substring",
                  "Position",
                  "Isnull",
                  "Is_not_null_pred",
                  "Extract",
                  "Timestampdiff",
                  "Timestampadd",
                  "Date_add",
                  "Date_sub",
                  "DateFloor",
                  "DateCeil",
                  "ArrayRange",
                  "CurrentDate",
                  "CurrentTime",
                  "CurrentTimestamp",
                  "LocalTime",
                  "LocalTimestamp",
                  "CurrentUser",
                  "SessionUser")
              .contains(node.kind())) functions.add(upper(node.tokens().get(0)));
      for (SqlNode child : node.children()) if (!child.rule().equals("cte")) walk(child, scope);
    }

    void findLimit(SqlNode node) {
      SqlNode organization = node.child("queryOrganization");
      SqlNode clause = organization == null ? null : organization.child("limitClause");
      if (clause != null) {
        limit = number(clause.fieldText("limit"), "LIMIT");
        offset = number(clause.fieldText("offset"), "OFFSET");
        return;
      }
      // Only the direct query spine can carry this query's LIMIT. Never visit FROM, CTE or
      // expression subqueries.
      if (node.rule().equals("query")) {
        SqlNode term = node.child("queryTerm");
        if (term != null) findLimit(term);
      } else if (node.kind().equals("QueryTermDefault")) {
        SqlNode p = node.child("queryPrimary");
        if (p != null) findLimit(p);
      } else if (node.kind().equals("QueryPrimaryDefault")) {
        SqlNode s = node.child("querySpecification");
        if (s != null) findLimit(s);
      } else if (node.kind().equals("Subquery")) {
        SqlNode q = node.child("query");
        if (q != null) findLimit(q);
      }
    }

    Long number(String value, String clause) {
      if (value == null) return null;
      try {
        return Long.valueOf(value);
      } catch (NumberFormatException e) {
        warn(clause + " exceeds the signed 64-bit metadata range: " + value);
        return null;
      }
    }

    TableDefinition definition(SqlNode node) {
      DdlExtractor ddl = new DdlExtractor(noBackslashEscapes);
      List<String> top = topTokens(node);
      List<String> keys = identifiers(node.field("keys"));
      List<ColumnDefinition> columns = new ArrayList<>();
      SqlNode defs = node.child("columnDefs");
      if (defs != null)
        for (SqlNode column : defs.children("columnDef")) columns.add(column(column, keys));
      SqlNode simple = node.child("simpleColumnDefs");
      if (simple != null)
        for (SqlNode column : simple.children("simpleColumnDef"))
          columns.add(
              new ColumnDefinition(
                  unquote(column.fieldText("colName")),
                  null,
                  null,
                  null,
                  literal(column.fieldText("comment")),
                  null,
                  false,
                  false,
                  null,
                  null,
                  null,
                  null,
                  column.syntax()));
      String keyType = null;
      int keyIndex = index(top, "KEY");
      if (keyIndex > 0
          && Set.of("AGGREGATE", "UNIQUE", "DUPLICATE").contains(upper(top.get(keyIndex - 1))))
        keyType = upper(top.get(keyIndex - 1));
      if (keyType == null && node.kind().equals("CreateMTMV") && keyIndex >= 0)
        keyType = "DUPLICATE";
      String distribution = null;
      int distributed = index(top, "DISTRIBUTED");
      if (distributed >= 0 && distributed + 2 < top.size())
        distribution = upper(top.get(distributed + 2));
      Map<String, String> properties = new LinkedHashMap<>();
      SqlNode prop = node.field("properties");
      if (prop == null) prop = node.child("propertyClause");
      if (prop != null)
        for (SqlNode item : descendants(prop, "propertyItem")) {
          String key = literal(item.fieldText("key"));
          if (properties.containsKey(key))
            warn("Duplicate property key " + key + "; properties map retains the last occurrence.");
          properties.put(key, literal(item.fieldText("value")));
        }
      SqlNode partition = field(node, "partition");
      if (partition == null) partition = node.child("mvPartition");
      SqlNode query = node.child("query");
      if (node.field("ctasCols") != null)
        attributes.put("ctasColumns", node.field("ctasCols").text());
      return new TableDefinition(
          table(field(node, "name", "mvName")),
          columns,
          has(top, "EXISTS"),
          has(top, "EXTERNAL"),
          has(top, "TEMPORARY"),
          unquote(node.fieldText("engine")),
          keyType,
          keys,
          literal(after(top, "COMMENT")),
          partition == null ? null : partition.text(),
          distribution,
          identifiers(node.field("hashKeys")),
          after(top, "BUCKETS"),
          properties,
          query == null ? null : query.text(),
          ddl.partition(partition),
          distribution == null
              ? null
              : new DistributionDefinition(
                  distribution, identifiers(node.field("hashKeys")), after(top, "BUCKETS")),
          identifiers(node.field("clusterKeys")),
          ddl.indexes(node),
          ddl.rollups(node),
          ddl.properties(prop),
          ddl.propertyMap(node.field("extProperties")),
          ddl.properties(node.field("extProperties")),
          identifiers(node.field("ctasCols")),
          node.syntax());
    }

    ColumnDefinition column(SqlNode node, List<String> keys) {
      String name = unquote(node.fieldText("colName"));
      SqlNode type = node.field("type");
      List<String> direct = node.directTokens();
      int nullIndex = index(direct, "NULL"), defaultIndex = index(direct, "DEFAULT");
      Boolean nullable = null;
      if (nullIndex >= 0 && (defaultIndex < 0 || nullIndex < defaultIndex))
        nullable = nullIndex == 0 || !direct.get(nullIndex - 1).equalsIgnoreCase("NOT");
      int def = node.directTokenIndex("DEFAULT"),
          on = node.directTokenIndex("ON"),
          comment = node.directTokenIndex("COMMENT");
      int count = node.tokens().size();
      String defaultValue =
          def < 0
              ? null
              : node.tokenSlice(
                  def + 1, Math.min(on < 0 ? count : on, comment < 0 ? count : comment));
      String onUpdate = on < 0 ? null : node.tokenSlice(on + 2, comment < 0 ? count : comment);
      return new ColumnDefinition(
          name,
          type.text(),
          nullable,
          defaultValue,
          literal(node.fieldText("comment")),
          node.fieldText("aggType"),
          has(direct, "KEY") || keys.stream().anyMatch(k -> k.equalsIgnoreCase(name)),
          has(direct, "AUTO_INCREMENT"),
          node.fieldText("generatedExpr"),
          node.fieldText("autoIncInitValue"),
          onUpdate,
          type.syntax(),
          node.syntax());
    }
  }

  private static SqlNode unwrap(SqlNode node) {
    while (node.children().size() == 1
        && (node.kind().endsWith("Alias")
            || node.kind().equals("Unsupported")
            || node.rule().equals("unsupportedStatement")
            || node.rule().equals("singleStatement"))) node = node.children().get(0);
    return node;
  }

  private static StatementType classify(SqlNode n) {
    return switch (n.kind()) {
      case "StatementDefault" -> StatementType.SELECT;
      case "InsertTable" ->
          has(topTokens(n), "OVERWRITE")
              ? StatementType.INSERT_OVERWRITE
              : isSelectQuery(n.child("query"))
                  ? StatementType.INSERT_SELECT
                  : StatementType.INSERT;
      case "Update" -> StatementType.UPDATE;
      case "Delete" -> StatementType.DELETE;
      case "MergeInto" -> StatementType.MERGE;
      case "CreateTable" ->
          n.child("query") == null
              ? StatementType.CREATE_TABLE
              : StatementType.CREATE_TABLE_AS_SELECT;
      case "CreateTableLike" -> StatementType.CREATE_TABLE_LIKE;
      case "CreateView" -> StatementType.CREATE_VIEW;
      case "CreateMTMV" -> StatementType.CREATE_MATERIALIZED_VIEW;
      case "DropMV", "DropMTMV" -> StatementType.DROP_MATERIALIZED_VIEW;
      case "AlterTable",
              "AlterTableExecute",
              "AlterTableAddRollup",
              "AlterTableDropRollup",
              "AlterTableProperties",
              "AddConstraint",
              "DropConstraint" ->
          StatementType.ALTER_TABLE;
      case "AlterView" -> StatementType.ALTER_VIEW;
      case "DropTable" -> StatementType.DROP_TABLE;
      case "DropView" -> StatementType.DROP_VIEW;
      case "TruncateTable" -> StatementType.TRUNCATE_TABLE;
      case "CreateDatabase" -> StatementType.CREATE_DATABASE;
      case "AlterDatabaseRename", "AlterDatabaseSetQuota", "AlterDatabaseProperties" ->
          StatementType.ALTER_DATABASE;
      case "DropDatabase" -> StatementType.DROP_DATABASE;
      case "CreateCatalog" -> StatementType.CREATE_CATALOG;
      case "DropCatalog" -> StatementType.DROP_CATALOG;
      case "Load" -> StatementType.LOAD;
      case "Export" -> StatementType.EXPORT;
      case "CopyInto" -> StatementType.COPY;
      default -> family(n);
    };
  }

  private static StatementType family(SqlNode node) {
    String kind = node.kind(), rule = node.rule();
    if (kind.startsWith("Show")) return StatementType.SHOW;
    if (rule.endsWith("UseStatement")) return StatementType.USE;
    if (rule.endsWith("DescribeStatement")) return StatementType.DESCRIBE;
    if (rule.endsWith("SetStatement")) return StatementType.SET;
    if (rule.endsWith("UnsetStatement")) return StatementType.RESET;
    if (kind.startsWith("Analyze")) return StatementType.ANALYZE;
    if (kind.startsWith("Grant")) return StatementType.GRANT;
    if (kind.startsWith("Revoke")) return StatementType.REVOKE;
    return StatementType.OTHER;
  }

  private static boolean isSelectQuery(SqlNode node) {
    if (node == null) return false;
    if (node.rule().equals("querySpecification") || node.kind().equals("SetOperation")) return true;
    if (node.kind().equals("ValuesTable")) return false;
    for (SqlNode child : node.children())
      if (Set.of("query", "queryTerm", "queryPrimary", "querySpecification").contains(child.rule())
          && isSelectQuery(child)) return true;
    return false;
  }
}
