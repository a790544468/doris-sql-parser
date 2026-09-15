package io.github.dorisparser.internal.metadata;

import static io.github.dorisparser.internal.metadata.Nodes.*;

import io.github.dorisparser.internal.SqlNode;
import io.github.dorisparser.model.SyntaxNode;
import io.github.dorisparser.model.query.*;
import java.util.*;

/** Version-neutral scoped query metadata derived from grammar nodes, never schema guesses. */
public final class QueryExtractor {
  private QueryExtractor() {}

  public static QueryInfo extract(SqlNode root) {
    return extract(root, false);
  }

  public static QueryInfo extract(SqlNode root, boolean noBackslashEscapes) {
    if (root == null) return null;
    SqlNode query = root.rule().equals("query") ? root : root.child("query");
    return query == null ? null : new Extraction(noBackslashEscapes).query(query);
  }

  public static InsertInfo insert(SqlNode root) {
    return insert(root, false);
  }

  public static InsertInfo insert(SqlNode root, boolean noBackslashEscapes) {
    return root != null && root.kind().equals("InsertTable")
        ? new Extraction(noBackslashEscapes).insert(root)
        : null;
  }

  private static final class Extraction {
    private final boolean noBackslashEscapes;

    Extraction(boolean noBackslashEscapes) {
      this.noBackslashEscapes = noBackslashEscapes;
    }

    QueryInfo query(SqlNode node) {
      if (node.rule().equals("query")) {
        QueryBuilder b = new QueryBuilder(query(node.child("queryTerm")));
        b.syntax = node.syntax();
        b.ctes = ctes(node.child("cte"));
        organization(node.child("queryOrganization"), b);
        return b.build();
      }
      if (node.kind().equals("SetOperation")) {
        QueryBuilder b = new QueryBuilder(QueryInfo.Kind.SET_OPERATION, node);
        b.setOperation =
            new SetOperationInfo(
                upper(node.fieldText("operator")),
                text(node.child("setQuantifier")),
                query(node.field("left")),
                query(node.field("right")),
                node.syntax());
        return b.build();
      }
      if (node.rule().equals("queryTerm")) return query(node.child("queryPrimary"));
      if (node.kind().equals("Subquery")) {
        QueryBuilder b = new QueryBuilder(QueryInfo.Kind.PARENTHESIZED, node);
        b.nestedQuery = query(node.child("query"));
        return b.build();
      }
      if (node.child("querySpecification") != null) return query(node.child("querySpecification"));
      if (node.child("inlineTable") != null) return query(node.child("inlineTable"));
      if (node.rule().equals("inlineTable")) {
        QueryBuilder b = new QueryBuilder(QueryInfo.Kind.VALUES, node);
        b.valuesRows =
            node.children("rowConstructor").stream()
                .map(
                    row ->
                        row.children("rowConstructorItem").stream()
                            .map(this::rowExpression)
                            .toList())
                .toList();
        return b.build();
      }
      if (node.rule().equals("querySpecification")) {
        QueryBuilder b = new QueryBuilder(QueryInfo.Kind.SELECT, node);
        SqlNode select = node.child("selectClause");
        b.quantifier =
            select.directTokens().stream()
                .map(Nodes::upper)
                .filter(t -> t.equals("DISTINCT") || t.equals("ALL"))
                .findFirst()
                .orElse(null);
        SqlNode columns = select.child("selectColumnClause");
        // Doris 2.1 represents '* EXCEPT (...)' outside primaryExpression.
        if (has(columns.directTokens(), "*")) {
          b.selectItems =
              List.of(new SelectItem(expression(columns), null, true, columns.syntax()));
        } else {
          SqlNode seq = columns.child("namedExpressionSeq");
          b.selectItems =
              seq == null
                  ? List.of()
                  : seq.children("namedExpression").stream()
                      .map(
                          n -> {
                            SqlNode e = n.child("expression");
                            return new SelectItem(
                                expression(e),
                                alias(n.child("identifierOrText")),
                                isProjectionStar(e),
                                n.syntax());
                          })
                      .toList();
        }
        SqlNode from = node.child("fromClause");
        if (from != null && from.child("relations") != null)
          b.relations = relations(from.child("relations"));
        b.where = clauseExpression(node.child("whereClause"));
        b.groupBy = grouping(node.child("aggClause"));
        b.having = clauseExpression(node.child("havingClause"));
        b.qualify = clauseExpression(node.child("qualifyClause"));
        b.hints = select.children("selectHint").stream().map(SqlNode::syntax).toList();
        organization(node.child("queryOrganization"), b);
        return b.build();
      }
      QueryBuilder b = new QueryBuilder(QueryInfo.Kind.OTHER, node);
      b.warnings = List.of("Query alternative has syntax-only coverage: " + node.kind());
      return b.build();
    }

    List<CteInfo> ctes(SqlNode cte) {
      if (cte == null) return List.of();
      return cte.children("aliasQuery").stream()
          .map(
              n ->
                  new CteInfo(
                      identifier(n.child("identifier")), identifiers(n.child("columnAliases")),
                      query(n.child("query")), n.syntax()))
          .toList();
    }

    void organization(SqlNode node, QueryBuilder b) {
      if (node == null) return;
      SqlNode sort = node.child("sortClause"), limit = node.child("limitClause");
      if (sort != null) b.orderBy = sort.children("sortItem").stream().map(this::order).toList();
      if (limit != null)
        b.limit =
            new LimitInfo(limit.fieldText("limit"), limit.fieldText("offset"), limit.syntax());
    }

    OrderItem order(SqlNode node) {
      var tokens = node.directTokens();
      return new OrderItem(
          expression(node.child("expression")),
          upper(node.fieldText("ordering")),
          upper(after(tokens, "NULLS")),
          node.syntax());
    }

    GroupByInfo grouping(SqlNode node) {
      if (node == null) return null;
      SqlNode g = node.child("groupingElement");
      var tokens = g.directTokens();
      String kind =
          has(tokens, "GROUPING")
              ? "GROUPING SETS"
              : has(tokens, "CUBE")
                  ? "CUBE"
                  : has(tokens, "WITH")
                      ? "WITH ROLLUP"
                      : has(tokens, "ROLLUP") ? "ROLLUP" : "SIMPLE";
      List<OrderItem> ordered =
          g.children("expressionWithOrder").stream().map(this::order).toList();
      List<ExpressionInfo> expressions =
          new ArrayList<>(g.children("expression").stream().map(this::expression).toList());
      expressions.addAll(ordered.stream().map(OrderItem::expression).toList());
      var sets =
          g.children("groupingSet").stream()
              .map(s -> s.children("expression").stream().map(this::expression).toList())
              .toList();
      return new GroupByInfo(kind, expressions, sets, ordered, node.syntax());
    }

    List<RelationInfo> relations(SqlNode node) {
      return node.children("relation").stream().map(this::relation).toList();
    }

    RelationInfo relation(SqlNode node) {
      if (node.rule().equals("relation")) {
        RelationInfo p = relation(node.child("relationPrimary"));
        return new RelationInfo(
            p.kind(),
            p.table(),
            p.function(),
            p.alias(),
            p.columnAliases(),
            p.subquery(),
            p.members(),
            node.children("joinRelation").stream().map(this::join).toList(),
            p.hints(),
            p.lateralViews(),
            node.syntax());
      }
      RelationInfo.Kind kind =
          switch (node.kind()) {
            case "TableName" -> RelationInfo.Kind.TABLE;
            case "AliasedQuery" -> RelationInfo.Kind.SUBQUERY;
            case "TableValuedFunction" -> RelationInfo.Kind.TABLE_FUNCTION;
            case "RelationList" -> RelationInfo.Kind.GROUP;
            default -> RelationInfo.Kind.OTHER;
          };
      SqlNode alias = node.child("tableAlias");
      return new RelationInfo(
          kind,
          table(node.child("multipartIdentifier")),
          identifier(node.field("tvfName")),
          alias == null ? null : identifier(alias.child("strictIdentifier")),
          alias == null ? List.of() : identifiers(alias.child("identifierList")),
          node.child("query") == null ? null : query(node.child("query")),
          node.child("relations") == null ? List.of() : relations(node.child("relations")),
          List.of(),
          node.children("relationHint").stream().map(SqlNode::syntax).toList(),
          node.children("lateralView").stream().map(this::lateral).toList(),
          node.syntax());
    }

    JoinInfo join(SqlNode node) {
      SqlNode type = node.child("joinType"), criteria = node.child("joinCriteria");
      String joinType =
          type == null ? "INNER" : String.join(" ", type.tokens()).toUpperCase(Locale.ROOT);
      if (joinType.isEmpty()) joinType = "INNER";
      return new JoinInfo(
          joinType,
          relation(node.field("right")),
          criteria == null ? null : clauseExpression(criteria),
          criteria == null ? List.of() : identifiers(criteria.child("identifierList")),
          node.children("distributeType").stream().map(SqlNode::syntax).toList(),
          clauseExpression(node.child("matchCondition")),
          node.syntax());
    }

    LateralViewInfo lateral(SqlNode node) {
      var ids = node.children("identifier");
      return new LateralViewInfo(
          identifier(node.field("functionName")),
          node.children("expression").stream().map(this::expression).toList(),
          identifier(node.field("tableName")),
          ids.stream().skip(2).map(Extraction::identifier).toList(),
          node.syntax());
    }

    ExpressionInfo rowExpression(SqlNode node) {
      SqlNode named = node.child("namedExpression");
      return expression(named == null ? node : named.child("expression"));
    }

    ExpressionInfo clauseExpression(SqlNode clause) {
      if (clause == null) return null;
      SqlNode node = Nodes.field(clause, "expression");
      if (node == null) node = clause.child("booleanExpression");
      if (node == null) node = clause.child("valueExpression");
      if (node == null) node = clause.child("expression");
      return node == null ? null : expression(node);
    }

    ExpressionInfo expression(SqlNode node) {
      List<ColumnReference> refs = new ArrayList<>();
      List<QueryInfo> subqueries = new ArrayList<>();
      expressionParts(node, refs, subqueries, Set.of());
      return new ExpressionInfo(node.syntax(), refs, subqueries);
    }

    void expressionParts(
        SqlNode node, List<ColumnReference> refs, List<QueryInfo> subqueries, Set<String> bound) {
      if (node.rule().equals("query")) {
        subqueries.add(query(node));
        return;
      }
      if (node.rule().equals("lambdaExpression")) {
        Set<String> local = new HashSet<>(bound);
        node.children("errorCapturingIdentifier")
            .forEach(n -> local.add(identifier(n).toUpperCase(Locale.ROOT)));
        expressionParts(node.field("body"), refs, subqueries, local);
        return;
      }
      List<String> chain = columnChain(node);
      if (chain != null) {
        if (!bound.contains(chain.get(0).toUpperCase(Locale.ROOT)))
          refs.add(new ColumnReference(chain, false, node.syntax()));
        return;
      }
      if (node.kind().equals("Star")
          || (node.rule().equals("selectColumnClause") && has(node.directTokens(), "*"))) {
        SqlNode qualifier = node.child("qualifiedName");
        List<String> parts = new ArrayList<>();
        if (qualifier != null)
          qualifier.children("identifier").forEach(n -> parts.add(identifier(n)));
        parts.add("*");
        refs.add(new ColumnReference(parts, true, node.syntax()));
        // 4.0 EXCEPT / REPLACE expressions still carry syntax references.
      }
      for (SqlNode child : node.children()) expressionParts(child, refs, subqueries, bound);
    }

    List<String> columnChain(SqlNode node) {
      if (node.kind().equals("ColumnReference"))
        return List.of(identifier(node.child("identifier")));
      if (!node.kind().equals("Dereference")) return null;
      List<String> base = columnChain(node.field("base"));
      if (base == null) return null;
      List<String> parts = new ArrayList<>(base);
      parts.add(identifier(node.field("fieldName")));
      return List.copyOf(parts);
    }

    boolean isProjectionStar(SqlNode node) {
      while (node != null) {
        if (node.kind().equals("Star")) return true;
        if (node.kind().equals("ParenthesizedExpression")) {
          node = node.child("expression");
          continue;
        }
        // Only transparent grammar wrappers may lead to a projection star.
        if (!Set.of("expression", "booleanExpression", "valueExpression").contains(node.rule())
            || node.children().size() != 1
            || !node.directTokens().isEmpty()) return false;
        node = node.children().get(0);
      }
      return false;
    }

    boolean isProjectionStar(SyntaxNode node) {
      while (node != null) {
        if (node.kind().equals("Star")) return true;
        if (node.kind().equals("ParenthesizedExpression")) {
          node =
              node.children().stream()
                  .filter(n -> n.rule().equals("expression"))
                  .findFirst()
                  .orElse(null);
          continue;
        }
        if (!Set.of("expression", "booleanExpression", "valueExpression").contains(node.rule())
            || node.children().size() != 1) return false;
        node = node.children().get(0);
      }
      return false;
    }

    InsertInfo insert(SqlNode root) {
      QueryInfo query = query(root.child("query"));
      List<String> columns = identifiers(root.field("cols"));
      List<String> reasons = new ArrayList<>();
      if (columns.isEmpty())
        reasons.add("TARGET_COLUMNS_NOT_EXPLICIT: target column names require a schema.");
      List<List<ExpressionInfo>> outputs = outputs(query, reasons);
      if (!outputs.isEmpty()) {
        int arity = outputs.get(0).size();
        if (outputs.stream().anyMatch(row -> row.size() != arity))
          reasons.add(
              "SOURCE_ARITY_MISMATCH: VALUES rows or set-operation branches differ in width.");
        if (!columns.isEmpty() && outputs.stream().anyMatch(row -> row.size() != columns.size()))
          reasons.add("TARGET_SOURCE_ARITY_MISMATCH: explicit target and source widths differ.");
      }
      List<ColumnMapping> mappings = new ArrayList<>();
      if (reasons.isEmpty()) {
        for (int i = 0; i < columns.size(); i++) {
          final int ordinal = i;
          mappings.add(
              new ColumnMapping(
                  columns.get(i), i + 1, outputs.stream().map(row -> row.get(ordinal)).toList()));
        }
      }
      return new InsertInfo(
          table(root.field("tableName")),
          root.fieldText("tableId"),
          columns,
          query,
          ctes(root.child("cte")),
          mappings,
          reasons,
          root.syntax());
    }

    List<List<ExpressionInfo>> outputs(QueryInfo q, List<String> reasons) {
      switch (q.kind()) {
        case SELECT:
          if (q.selectItems().stream().anyMatch(SelectItem::wildcard))
            reasons.add("SOURCE_WILDCARD: projection stars require schema expansion.");
          return List.of(q.selectItems().stream().map(SelectItem::expression).toList());
        case VALUES:
          if (q.valuesRows().stream()
              .flatMap(List::stream)
              .anyMatch(e -> isProjectionStar(e.syntax())))
            reasons.add("SOURCE_WILDCARD: a VALUES star has no explicit scalar output width.");
          return q.valuesRows();
        case PARENTHESIZED:
          return outputs(q.nestedQuery(), reasons);
        case SET_OPERATION:
          List<List<ExpressionInfo>> rows =
              new ArrayList<>(outputs(q.setOperation().left(), reasons));
          rows.addAll(outputs(q.setOperation().right(), reasons));
          return rows;
        default:
          reasons.add(
              "SOURCE_SHAPE_UNSUPPORTED: source output width cannot be determined from typed"
                  + " metadata.");
          return List.of();
      }
    }

    private static String text(SqlNode node) {
      return node == null ? null : upper(node.text());
    }

    private static String identifier(SqlNode node) {
      return node == null ? null : unquote(String.join("", node.tokens()));
    }

    private String alias(SqlNode node) {
      if (node == null) return null;
      String raw = String.join("", node.tokens());
      return raw.startsWith("'") || raw.startsWith("\"")
          ? stringLiteral(raw, noBackslashEscapes)
          : unquote(raw);
    }
  }

  private static final class QueryBuilder {
    QueryInfo.Kind kind;
    String quantifier;
    List<SelectItem> selectItems = List.of();
    List<RelationInfo> relations = List.of();
    ExpressionInfo where, having, qualify;
    GroupByInfo groupBy;
    List<OrderItem> orderBy = List.of();
    LimitInfo limit;
    List<CteInfo> ctes = List.of();
    SetOperationInfo setOperation;
    QueryInfo nestedQuery;
    List<List<ExpressionInfo>> valuesRows = List.of();
    List<SyntaxNode> hints = List.of();
    SyntaxNode syntax;
    List<String> warnings = List.of();

    QueryBuilder(QueryInfo.Kind kind, SqlNode node) {
      this.kind = kind;
      this.syntax = node.syntax();
    }

    QueryBuilder(QueryInfo q) {
      kind = q.kind();
      quantifier = q.quantifier();
      selectItems = q.selectItems();
      relations = q.relations();
      where = q.where();
      groupBy = q.groupBy();
      having = q.having();
      qualify = q.qualify();
      orderBy = q.orderBy();
      limit = q.limit();
      ctes = q.ctes();
      setOperation = q.setOperation();
      nestedQuery = q.nestedQuery();
      valuesRows = q.valuesRows();
      hints = q.hints();
      syntax = q.syntax();
      warnings = q.warnings();
    }

    QueryInfo build() {
      return new QueryInfo(
          kind,
          quantifier,
          selectItems,
          relations,
          where,
          groupBy,
          having,
          qualify,
          orderBy,
          limit,
          ctes,
          setOperation,
          nestedQuery,
          valuesRows,
          hints,
          syntax,
          warnings);
    }
  }
}
