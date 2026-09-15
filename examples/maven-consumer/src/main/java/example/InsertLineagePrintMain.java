package example;

import io.github.dorisparser.*;
import io.github.dorisparser.model.*;
import io.github.dorisparser.model.query.*;
import java.nio.file.*;
import java.util.*;

/**
 * 简洁的 INSERT 列来源打印示例。无需数据库，不打印完整解析树，不使用断言。 只追踪 SELECT/CTE/派生表的显式输出；物理表星号、歧义列、集合查询等明确拒绝。 输出是 SQL
 * 静态来源分析，不检查真实表结构；JOIN/WHERE 等行依赖不作为值来源列。
 */
public final class InsertLineagePrintMain {
  private static final String DEFAULT_SQL =
      """
INSERT INTO report.user_profile(user_id, user_name, total_amount, user_level, last_order_time)
SELECT x.*,
       CASE
           WHEN x.total_amount >= 1000 AND vip.user_id IS NOT NULL THEN 'VIP'
           WHEN x.total_amount > 0 THEN 'ACTIVE'
           ELSE 'NEW'
       END AS user_level,
       latest.last_order_time
FROM (
    SELECT u.id AS user_id,
           COALESCE(u.name, 'unknown') AS user_name,
           COALESCE(s.total_amount, 0) AS total_amount
    FROM users AS u
    LEFT JOIN (
        SELECT detail.user_id, SUM(detail.net_amount) AS total_amount
        FROM (
            SELECT o.user_id,
                   CASE WHEN o.status = 'PAID'
                        THEN o.amount - COALESCE(r.refund_amount, 0)
                        ELSE 0 END AS net_amount
            FROM orders AS o
            LEFT JOIN (
                SELECT order_id, SUM(amount) AS refund_amount
                FROM refunds
                WHERE status = 'SUCCESS'
                GROUP BY order_id
            ) AS r ON o.id = r.order_id
            WHERE EXISTS (
                SELECT 1 FROM order_items AS oi
                WHERE oi.order_id = o.id AND oi.quantity > 0
            )
        ) AS detail
        GROUP BY detail.user_id
    ) AS s ON u.id = s.user_id
    WHERE u.enabled = 1
      AND u.id IN (SELECT user_id FROM user_tags WHERE tag = 'target')
) AS x
LEFT JOIN (
    SELECT user_id, MAX(created_at) AS last_order_time
    FROM orders GROUP BY user_id
) AS latest ON x.user_id = latest.user_id
LEFT JOIN (
    SELECT DISTINCT user_id FROM memberships WHERE level = 'VIP'
) AS vip ON x.user_id = vip.user_id;
""";

  public static void main(String[] args) throws Exception {
    // 无参数：直接演示复杂 SQL。参数：SQL 文件路径，可选版本 2.1 / 4.0。
    String sql = args.length == 0 ? DEFAULT_SQL : Files.readString(Path.of(args[0]));
    DorisVersion version =
        args.length < 2 ? DorisVersion.DORIS_2_1 : DorisVersion.fromString(args[1]);
    try {
      print(new DorisSqlParser(version).parseStatement(sql));
    } catch (SqlParseException | UnsupportedOperationException e) {
      System.out.println("无法确定列来源：" + e.getMessage());
    }
  }

  public static void print(SqlStatement statement) {
    System.out.println(
        "来源表：" + statement.inputTables().stream().map(InsertLineagePrintMain::tableName).toList());
    if (statement.insert() == null || statement.explained()) {
      throw unsupported("请提供实际 INSERT SELECT 语句。");
    }
    var insert = statement.insert();
    String target = tableName(insert.targetTable());
    System.out.println("结果表：" + target);
    System.out.println("说明：按 SQL 显式来源追踪；来源列含 CASE 判断用列，不包含仅用于 JOIN/WHERE 的行筛选列。");
    System.out.println("公式按查询层级列出，不能直接拼成一层 SQL 执行。\n");
    if (insert.targetColumns().isEmpty()) throw unsupported("INSERT 未写目标列，需要提供目标表字段顺序。");
    var resolver = new Resolver();
    var ctes = resolver.withCtes(insert.ctes(), Map.of());
    var output = resolver.query(insert.query(), ctes, "");
    if (output.size() != insert.targetColumns().size()) {
      throw unsupported(
          "展开后的查询输出数 " + output.size() + " 与目标列数 " + insert.targetColumns().size() + " 不一致。");
    }
    // 先完整分析再打印，遇到歧义不会留下貌似完整的部分映射。
    var allSteps = new LinkedHashMap<String, String>();
    for (int i = 0; i < output.size(); i++) {
      var column = output.get(i);
      System.out.println((i + 1) + ". 结果字段：" + target + "." + insert.targetColumns().get(i));
      System.out.println(
          "   来源字段："
              + (column.value.sources.isEmpty()
                  ? "无显式来源列（例如常量或 COUNT(*)）"
                  : String.join(", ", column.value.sources)));
      System.out.println("   公式：" + column.value.formula);
      allSteps.putAll(column.value.steps);
      System.out.println();
    }
    if (!allSteps.isEmpty()) {
      System.out.println("计算过程（中间名称按子查询路径区分，重复步骤只打印一次）：");
      allSteps.values().forEach(step -> System.out.println("  " + step));
      System.out.println();
    }
    System.out.println("共 " + output.size() + " 个结果字段。JOIN、WHERE、EXISTS、IN 还会影响行是否保留及聚合结果。");
  }

  private record Value(
      String formula, Set<String> sources, Map<String, String> steps, boolean direct) {}

  private record Output(String name, Value value) {}

  private record Cte(QueryInfo query, List<String> aliases, Map<String, Cte> visible) {}

  private record Source(
      TableId table, List<Output> outputs, List<List<String>> qualifiers, String path) {}

  /** 示例级解析器；不作为库的完整 schema 绑定/血缘 API。 */
  private static final class Resolver {
    private int scopeId;

    Map<String, Cte> withCtes(List<CteInfo> definitions, Map<String, Cte> inherited) {
      var result = new LinkedHashMap<>(inherited);
      for (var c : definitions) {
        result.put(c.name(), new Cte(c.query(), c.columnAliases(), Map.copyOf(result)));
      }
      return result;
    }

    List<Output> query(QueryInfo q, Map<String, Cte> inherited, String path) {
      if (q == null) throw unsupported("缺少来源查询。");
      var ctes = withCtes(q.ctes(), inherited);
      if (q.kind() == QueryInfo.Kind.PARENTHESIZED) return query(q.nestedQuery(), ctes, path);
      if (q.kind() != QueryInfo.Kind.SELECT)
        throw unsupported("简洁示例暂不追踪 " + q.kind() + "，可用原综合 main 查看结构。");
      if (!q.warnings().isEmpty()) throw unsupported("查询包含未完整提取的结构：" + q.warnings());
      var sources = new ArrayList<Source>();
      for (var r : q.relations()) relation(r, ctes, path, sources);
      var output = new ArrayList<Output>();
      for (var item : q.selectItems()) {
        if (item.wildcard()) {
          var refs = item.expression().columnReferences();
          if (refs.size() != 1 || !refs.get(0).wildcard()) throw unsupported("无法确定星号限定范围。");
          var qualifier = refs.get(0).qualifier();
          var selected = qualifier.isEmpty() ? sources : qualified(sources, qualifier);
          if (selected.isEmpty() || (!qualifier.isEmpty() && selected.size() != 1))
            throw unsupported("星号限定名不存在或有歧义：" + qualifier);
          for (var s : selected) {
            if (s.outputs == null)
              throw unsupported("物理表 " + tableName(s.table) + " 的 * 需要 schema 才能展开。");
            for (var field : s.outputs) output.add(new Output(field.name, reference(s, field)));
          }
        } else {
          String name = item.alias();
          if (name == null && item.expression().columnReferences().size() == 1) {
            var ref = item.expression().columnReferences().get(0);
            if (ref.syntax().span().equals(item.expression().syntax().span())) name = ref.column();
          }
          output.add(new Output(name, expression(item.expression(), sources)));
        }
      }
      // 分组信息保留在计算步骤，避免把不同聚合层级误认为同一层。
      if (q.groupBy() != null) {
        String grouping = oneLine(q.groupBy().syntax().text());
        for (int i = 0; i < output.size(); i++) {
          var o = output.get(i);
          if (!o.value.direct) {
            var steps = new LinkedHashMap<>(o.value.steps);
            steps.put("group:" + path, "分组（" + (path.isEmpty() ? "外层" : path) + "）：" + grouping);
            output.set(
                i, new Output(o.name, new Value(o.value.formula, o.value.sources, steps, false)));
          }
        }
      }
      return output;
    }

    void relation(RelationInfo r, Map<String, Cte> ctes, String parent, List<Source> sources) {
      if (!r.lateralViews().isEmpty()) throw unsupported("LATERAL VIEW 输出需要专门绑定。");
      String alias = r.alias();
      String path =
          (parent.isEmpty() ? "" : parent + ".") + (alias != null ? alias : "q" + (++scopeId));
      List<Output> outputs = null;
      TableId table = r.table();
      if (r.kind() == RelationInfo.Kind.SUBQUERY) {
        outputs = query(r.subquery(), ctes, path);
      } else if (r.kind() == RelationInfo.Kind.TABLE) {
        if (table.parts().size() == 1 && ctes.containsKey(table.tableName())) {
          var cte = ctes.get(table.tableName());
          outputs = rename(query(cte.query, cte.visible, path), cte.aliases);
        }
      } else {
        throw unsupported("简洁示例暂不绑定来源类型 " + r.kind());
      }
      if (!r.columnAliases().isEmpty()) {
        if (outputs == null) throw unsupported("物理表列别名列表需要 schema。");
        outputs = rename(outputs, r.columnAliases());
      }
      var qualifiers = new ArrayList<List<String>>();
      if (alias != null) qualifiers.add(List.of(alias));
      else if (table != null) {
        qualifiers.add(table.parts());
        for (int start = 1; start < table.parts().size(); start++)
          qualifiers.add(table.parts().subList(start, table.parts().size()));
      }
      sources.add(new Source(table, outputs, qualifiers, path));
      for (var join : r.joins()) {
        if (!join.usingColumns().isEmpty() || join.type().contains("NATURAL"))
          throw unsupported("USING/NATURAL JOIN 的合并列需要专门绑定。");
        if (join.type().contains("SEMI") || join.type().contains("ANTI"))
          throw unsupported("SEMI/ANTI JOIN 的可见列需专门处理。");
        relation(join.right(), ctes, parent, sources);
      }
    }

    List<Output> rename(List<Output> fields, List<String> aliases) {
      if (aliases.isEmpty()) return fields;
      if (aliases.size() != fields.size()) throw unsupported("派生表/CTE 列别名数量不一致。");
      var renamed = new ArrayList<Output>();
      for (int i = 0; i < fields.size(); i++)
        renamed.add(new Output(aliases.get(i), fields.get(i).value));
      return renamed;
    }

    List<Source> qualified(List<Source> sources, List<String> qualifier) {
      return sources.stream().filter(s -> s.qualifiers.contains(qualifier)).toList();
    }

    Value column(ColumnReference ref, List<Source> sources) {
      if (ref.wildcard()) throw unsupported("表达式中的星号无法按单列追踪。");
      var candidates =
          ref.qualifier().isEmpty()
              ? sources.stream()
                  .filter(
                      s ->
                          s.outputs == null
                              || s.outputs.stream()
                                  .anyMatch(o -> Objects.equals(o.name, ref.column())))
                  .toList()
              : qualified(sources, ref.qualifier());
      if (candidates.size() != 1)
        throw unsupported("列 " + ref.syntax().text() + " 的来源缺失或不唯一，需要 schema/明确限定名。");
      var source = candidates.get(0);
      if (source.outputs == null) {
        String name = tableName(source.table) + "." + ref.column();
        return new Value(name, new LinkedHashSet<>(List.of(name)), new LinkedHashMap<>(), true);
      }
      var matches =
          source.outputs.stream().filter(o -> Objects.equals(o.name, ref.column())).toList();
      if (matches.size() != 1) throw unsupported("派生表列缺失或重复：" + ref.syntax().text());
      return reference(source, matches.get(0));
    }

    Value reference(Source source, Output field) {
      if (field.name == null) throw unsupported("派生表中的计算列需要显式 AS 名称。");
      if (field.value.direct) return field.value;
      String label = source.path + "." + field.name;
      var steps = new LinkedHashMap<>(field.value.steps);
      steps.put(label, label + " = " + field.value.formula);
      return new Value(label, field.value.sources, steps, true);
    }

    Value expression(ExpressionInfo expr, List<Source> sources) {
      if (!expr.subqueries().isEmpty()) throw unsupported("值表达式中的标量/相关子查询暂不追踪。");
      if (!expr.syntax().descendants("windowSpec").isEmpty())
        throw unsupported("窗口函数还需追踪窗口定义，本示例不做完整绑定。");
      // 非词法替换：根据 AST 列引用的 Unicode 码点位置替换，避免修改字符串/同名子串。
      var refs = new ArrayList<>(expr.columnReferences());
      // COUNT(*) 的星号表示计数输入行，不是需要绑定/展开的输出字段。
      refs.removeIf(
          ref ->
              ref.wildcard()
                  && expr.syntax().descendants("functionCallExpression").stream()
                      .anyMatch(
                          call ->
                              call.text().matches("(?is)COUNT\\s*\\(\\s*\\*\\s*\\)")
                                  && call.span().startOffset() <= ref.syntax().span().startOffset()
                                  && call.span().endOffset() >= ref.syntax().span().endOffset()));
      refs.sort(Comparator.comparingInt(r -> r.syntax().span().startOffset()));
      String original = expr.text();
      int base = expr.syntax().span().startOffset(), cursor = 0;
      var formula = new StringBuilder();
      var fields = new LinkedHashSet<String>();
      var steps = new LinkedHashMap<String, String>();
      for (var ref : refs) {
        int start = ref.syntax().span().startOffset() - base;
        int end = ref.syntax().span().endOffset() - base;
        if (start < cursor) throw unsupported("列引用范围重叠，需要更精确的表达式绑定。");
        var value = column(ref, sources);
        formula.append(slice(original, cursor, start)).append(value.formula);
        fields.addAll(value.sources);
        steps.putAll(value.steps);
        cursor = end;
      }
      formula.append(slice(original, cursor, original.codePointCount(0, original.length())));
      boolean direct = refs.size() == 1 && refs.get(0).syntax().span().equals(expr.syntax().span());
      return new Value(oneLine(formula.toString()), fields, steps, direct);
    }
  }

  private static String slice(String s, int from, int to) {
    return s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, to));
  }

  // 仅压缩字符串和标识符引号之外的空白，保留常量中的连续空格。
  private static String oneLine(String sql) {
    var out = new StringBuilder();
    char quote = 0;
    boolean pendingSpace = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quote != 0) {
        out.append(c);
        if (c == '\\' && i + 1 < sql.length()) out.append(sql.charAt(++i));
        else if (c == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) out.append(sql.charAt(++i));
          else quote = 0;
        }
      } else if (Character.isWhitespace(c)) {
        pendingSpace = true;
      } else {
        if (pendingSpace && !out.isEmpty()) out.append(' ');
        pendingSpace = false;
        out.append(c);
        if (c == '\'' || c == '"' || c == '`') quote = c;
      }
    }
    return out.toString();
  }

  private static String tableName(TableId table) {
    return String.join(".", table.parts());
  }

  private static UnsupportedOperationException unsupported(String reason) {
    return new UnsupportedOperationException(reason);
  }
}
