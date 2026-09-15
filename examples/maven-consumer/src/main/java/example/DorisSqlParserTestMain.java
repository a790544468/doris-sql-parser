package example;

import io.github.dorisparser.*;
import io.github.dorisparser.model.*;
import io.github.dorisparser.model.query.*;
import java.util.*;

/**
 * 纯打印示例：直接运行 main；不使用 assert、JUnit 或结果断言，不连接数据库。 每条 SQL 单独捕获异常，出错后继续下一条。默认分别使用 Doris 2.1 和 4.0。
 * --version 2.1|4.0|all --category DDL|DML|QUERY|CTE|ADVANCED|COMMAND|VERSION|INVALID|API --tree
 * 打印完整语法树；--list 仅列出示例；--sql "SELECT ..." 临时解析自己的 SQL。
 */
public final class DorisSqlParserTestMain {
  private enum Mode {
    STATEMENT,
    SCRIPT,
    EXPRESSION
  }

  private record Sample(String category, String name, String sql, Mode mode) {}

  public static void main(String[] args) {
    List<DorisVersion> versions = List.of(DorisVersion.values());
    String category = null, customSql = null;
    boolean tree = false, list = false;
    try {
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--version" -> {
            String v = argument(args, ++i);
            versions =
                v.equalsIgnoreCase("all")
                    ? List.of(DorisVersion.values())
                    : List.of(DorisVersion.fromString(v));
          }
          case "--category" -> category = argument(args, ++i).toUpperCase(Locale.ROOT);
          case "--sql" -> customSql = argument(args, ++i);
          case "--tree" -> tree = true;
          case "--list" -> list = true;
          case "--help", "-h" -> {
            help();
            return;
          }
          default -> throw new IllegalArgumentException("未知参数：" + args[i]);
        }
      }
    } catch (IllegalArgumentException e) {
      System.out.println(e.getMessage());
      help();
      return;
    }
    List<Sample> samples =
        customSql == null
            ? samples()
            : List.of(new Sample("CUSTOM", "自定义 SQL", customSql, Mode.SCRIPT));
    if (category != null) {
      String filter = category;
      samples = samples.stream().filter(s -> s.category().equals(filter)).toList();
    }
    if (samples.isEmpty()) {
      System.out.println("没有匹配的示例，请使用 --list 查看分类。");
      return;
    }
    if (list) {
      for (int i = 0; i < samples.size(); i++)
        System.out.printf(
            "%03d [%s] %s%n", i + 1, samples.get(i).category(), samples.get(i).name());
      return;
    }
    System.out.printf(
        "Doris SQL Parser 0.2.0 | Java %s | %d 个示例 | 版本 %s%n",
        System.getProperty("java.version"), samples.size(), versions);
    System.out.println("仅解析并打印，不连接数据库、不执行 SQL、不做结果断言。");
    System.out.println("VERSION 演示版本差异；INVALID 故意提供错误 SQL。这些解析报错也会计入汇总。");
    int parsed = 0, syntaxErrors = 0, otherErrors = 0;
    Map<String, int[]> totals = new LinkedHashMap<>();
    for (DorisVersion version : versions) {
      var parser = new DorisSqlParser(version);
      for (int index = 0; index < samples.size(); index++) {
        Sample sample = samples.get(index);
        int[] counts =
            totals.computeIfAbsent(version.id() + " / " + sample.category(), ignored -> new int[3]);
        System.out.println("\n" + "=".repeat(96));
        System.out.printf(
            "[%s] %03d/%03d [%s] %s%n",
            version.id(), index + 1, samples.size(), sample.category(), sample.name());
        System.out.println("SQL:\n" + sample.sql());
        try {
          switch (sample.mode()) {
            case STATEMENT -> printStatement(parser.parseStatement(sample.sql()), tree);
            case SCRIPT -> {
              var statements = parser.parseMultiStatement(sample.sql());
              System.out.println("拆分结果：" + parser.splitSql(sample.sql()));
              for (int n = 0; n < statements.size(); n++) {
                System.out.println("\n第 " + (n + 1) + " 条语句：");
                printStatement(statements.get(n), tree);
              }
              var document = parser.parseSyntax(sample.sql());
              System.out.println("完整原文长度(UTF-16)：" + document.source().length());
              System.out.println("词法 token 数（含空白/注释）：" + document.tokens().size());
              document.tokens().stream()
                  .filter(SyntaxToken::hidden)
                  .forEach(
                      t ->
                          System.out.printf(
                              "  隐藏 token %s [%d,%d): %s%n",
                              t.kind(),
                              t.span().startOffset(),
                              t.span().endOffset(),
                              escaped(t.text())));
            }
            case EXPRESSION -> {
              var expression = parser.parseExpressionSyntax(sample.sql());
              System.out.println("表达式原文：" + expression.text());
              System.out.println("根规则：" + expression.rule() + " / " + expression.kind());
              System.out.println("原文位置：" + expression.span());
              System.out.println("表达式语法树：" + parser.parseExpression(sample.sql()));
              if (tree) printTree(expression, "");
            }
          }
          parsed++;
          counts[0]++;
          System.out.println("执行结果：解析完成");
        } catch (SqlParseException e) {
          syntaxErrors++;
          counts[1]++;
          System.out.printf(
              "执行结果：语法解析错误，版本=%s，第%d行，第%d列，token=%s%n",
              e.version().id(), e.line(), e.column(), e.offendingToken());
          System.out.println("原因：" + e.getMessage());
        } catch (RuntimeException e) {
          otherErrors++;
          counts[2]++;
          System.out.println("执行结果：其他异常，" + e.getClass().getSimpleName() + ": " + e.getMessage());
          e.printStackTrace(System.out);
        }
      }
    }
    System.out.println("\n" + "=".repeat(96));
    System.out.printf(
        "汇总：执行 %d 次，解析完成 %d 次，语法解析错误 %d 次，其他异常 %d 次。%n",
        parsed + syntaxErrors + otherErrors, parsed, syntaxErrors, otherErrors);
    System.out.println("以下仅统计运行结果，不表示断言通过或数据库执行成功：");
    totals.forEach(
        (group, c) ->
            System.out.printf("  %-20s 解析完成=%d 语法错误=%d 其他异常=%d%n", group, c[0], c[1], c[2]));
  }

  // ---------------------- 结果打印：按公开 API 读取 ----------------------
  private static void printStatement(SqlStatement s, boolean tree) {
    System.out.println("语句类型：" + s.statementType() + "；EXPLAIN=" + s.explained());
    System.out.println("输入表：" + s.inputTables().stream().map(TableId::qualifiedName).toList());
    System.out.println("输出/受影响表：" + s.outputTables().stream().map(TableId::qualifiedName).toList());
    System.out.println("函数：" + s.functionNames());
    System.out.println("摘要状态：" + s.metadataStatus() + "；警告：" + s.warnings());
    System.out.println("语法分支：" + s.attributes());
    if (s.tableDefinition() != null) printTable(s.tableDefinition());
    for (var action : s.alterActions())
      System.out.println("ALTER 动作：" + action.kind() + " => " + action.sql());
    if (s.insert() != null) {
      var i = s.insert();
      System.out.println(
          "INSERT 目标表：" + (i.targetTable() == null ? null : i.targetTable().qualifiedName()));
      System.out.println("INSERT 内部表 ID：" + i.targetTableId());
      System.out.println("INSERT 目标列：" + i.targetColumns());
      printCtes(i.ctes(), "  INSERT 前置 ");
      for (var mapping : i.columnMappings())
        System.out.printf(
            "  列对应：%s <- 第%d列 %s%n",
            mapping.targetColumn(),
            mapping.sourceOrdinal(),
            mapping.sourceExpressions().stream().map(ExpressionInfo::text).toList());
      System.out.println("INSERT 未解析原因：" + i.unresolvedReasons());
    }
    if (s.query() != null) printQuery(s.query(), "  ");
    // UPDATE、DELETE、ALTER、LOAD 及查询选项等仍可从通用语法树读取。
    for (String rule :
        List.of(
            "updateAssignment",
            "partitionSpec",
            "outFileClause",
            "sample",
            "tableSnapshot",
            "specifiedPartition",
            "propertyClause",
            "mergeMatchedClause",
            "mergeNotMatchedClause")) {
      for (var n : s.syntax().descendants(rule))
        System.out.println("语法片段 [" + rule + "]：" + n.text());
    }
    if (s.query() == null) {
      for (var where : s.syntax().descendants("whereClause"))
        System.out.println("WHERE 原文：" + where.text());
      for (var join : s.syntax().descendants("joinCriteria"))
        System.out.println("关联条件原文：" + join.text());
    }
    System.out.println("完整语句位置：" + s.syntax().span());
    if (tree) printTree(s.syntax(), "");
  }

  private static void printTable(TableDefinition d) {
    System.out.println("表定义：" + (d.table() == null ? null : d.table().qualifiedName()));
    System.out.printf(
        "  IF NOT EXISTS=%s EXTERNAL=%s TEMPORARY=%s ENGINE=%s COMMENT=%s%n",
        d.ifNotExists(), d.external(), d.temporary(), d.engine(), d.comment());
    for (var c : d.columns()) {
      System.out.printf(
          "  列：%s | 类型=%s | nullable=%s | key=%s | 聚合=%s | 默认值=%s | comment=%s%n",
          c.name(),
          c.dataType(),
          c.nullable(),
          c.key(),
          c.aggregateType(),
          c.defaultExpression(),
          c.comment());
      if (c.autoIncrement()) System.out.println("    自增初值：" + c.autoIncrementStart());
      if (c.generatedExpression() != null)
        System.out.println("    生成表达式：" + c.generatedExpression());
      if (c.onUpdateExpression() != null)
        System.out.println("    ON UPDATE：" + c.onUpdateExpression());
    }
    System.out.printf(
        "  KEY 类型=%s KEY列=%s CLUSTER列=%s 声明排序列=%s%n",
        d.keyType(), d.keyColumns(), d.clusterKeys(), d.sortColumns());
    if (d.partition() != null) {
      var p = d.partition();
      System.out.printf(
          "  分区：类型=%s AUTO=%s 列=%s 表达式=%s%n",
          p.type(),
          p.automatic(),
          p.columns(),
          p.expressions().stream().map(SyntaxNode::text).toList());
      for (var item : p.partitions()) {
        System.out.printf(
            "    %s %s 下界=%s 上界=%s IN元组=%s INTERVAL=%s %s properties=%s%n",
            item.kind(),
            item.name(),
            item.lowerBound(),
            item.upperBound(),
            item.inValues(),
            item.intervalAmount(),
            item.intervalUnit(),
            item.properties());
      }
    }
    if (d.distribution() != null)
      System.out.printf(
          "  分桶：%s 列=%s 桶数=%s AUTO=%s%n",
          d.distribution().type(),
          d.distribution().columns(),
          d.distribution().buckets(),
          d.distribution().autoBuckets());
    for (var i : d.indexes())
      System.out.printf(
          "  索引：%s 类型=%s 列=%s comment=%s properties=%s%n",
          i.name(), i.type(), i.columns(), i.comment(), i.properties());
    for (var r : d.rollups())
      System.out.printf(
          "  ROLLUP：%s 列=%s duplicateKeys=%s properties=%s%n",
          r.name(), r.columns(), r.duplicateKeys(), r.properties());
    System.out.println("  properties：" + d.properties());
    for (var p : d.propertyItems())
      System.out.printf("    原始属性：%s = %s%n", p.keySql(), p.valueSql());
    System.out.println("  BROKER properties：" + d.externalProperties());
    if (!d.ctasColumns().isEmpty()) System.out.println("  CTAS 列：" + d.ctasColumns());
    if (d.querySql() != null) System.out.println("  建表/视图查询：" + d.querySql());
  }

  private static void printQuery(QueryInfo q, String indent) {
    System.out.println(indent + "查询类型：" + q.kind() + "；量词：" + q.quantifier());
    printCtes(q.ctes(), indent);
    for (int n = 0; n < q.selectItems().size(); n++) {
      var item = q.selectItems().get(n);
      System.out.printf(
          "%s投影列%d：%s | AS=%s | 通配符=%s%n",
          indent, n + 1, item.expression().text(), item.alias(), item.wildcard());
      printExpression("列引用/表达式", item.expression(), indent + "  ");
    }
    for (var r : q.relations()) printRelation(r, indent);
    printExpression("WHERE", q.where(), indent);
    if (q.groupBy() != null) {
      System.out.println(
          indent
              + "GROUP BY "
              + q.groupBy().kind()
              + "："
              + q.groupBy().expressions().stream().map(ExpressionInfo::text).toList());
      System.out.println(
          indent
              + "GROUPING SETS："
              + q.groupBy().sets().stream()
                  .map(set -> set.stream().map(ExpressionInfo::text).toList())
                  .toList());
    }
    printExpression("HAVING", q.having(), indent);
    printExpression("QUALIFY", q.qualify(), indent);
    for (var order : q.orderBy())
      System.out.printf(
          "%sORDER BY：%s direction=%s nulls=%s%n",
          indent, order.expression().text(), order.direction(), order.nullOrdering());
    if (q.limit() != null)
      System.out.printf("%sLIMIT=%s OFFSET=%s%n", indent, q.limit().count(), q.limit().offset());
    if (q.setOperation() != null) {
      System.out.println(
          indent + "集合操作：" + q.setOperation().operator() + " " + q.setOperation().quantifier());
      System.out.println(indent + "左分支：");
      printQuery(q.setOperation().left(), indent + "  ");
      System.out.println(indent + "右分支：");
      printQuery(q.setOperation().right(), indent + "  ");
    }
    if (q.nestedQuery() != null) {
      System.out.println(indent + "括号中的查询：");
      printQuery(q.nestedQuery(), indent + "  ");
    }
    for (int n = 0; n < q.valuesRows().size(); n++) {
      System.out.println(
          indent
              + "VALUES 第"
              + (n + 1)
              + "行："
              + q.valuesRows().get(n).stream().map(ExpressionInfo::text).toList());
      for (var e : q.valuesRows().get(n))
        if (!e.subqueries().isEmpty()) printExpression("VALUES 子查询", e, indent + "  ");
    }
    if (!q.warnings().isEmpty()) System.out.println(indent + "查询警告：" + q.warnings());
  }

  private static void printCtes(List<CteInfo> ctes, String indent) {
    for (var c : ctes) {
      System.out.println(indent + "CTE：" + c.name() + "；列别名：" + c.columnAliases());
      printQuery(c.query(), indent + "  ");
    }
  }

  private static void printRelation(RelationInfo r, String indent) {
    System.out.printf(
        "%sFROM %s | 表=%s | 别名=%s | 列别名=%s | 表函数=%s%n",
        indent,
        r.kind(),
        r.table() == null ? null : r.table().qualifiedName(),
        r.alias(),
        r.columnAliases(),
        r.function());
    for (var hint : r.hints()) System.out.println(indent + "  表 hint：" + hint.text());
    if (r.subquery() != null) {
      System.out.println(indent + "  派生表查询：");
      printQuery(r.subquery(), indent + "    ");
    }
    for (var member : r.members()) printRelation(member, indent + "  ");
    for (var lateral : r.lateralViews())
      System.out.printf(
          "%sLATERAL VIEW %s(%s) %s AS %s%n",
          indent,
          lateral.function(),
          lateral.arguments().stream().map(ExpressionInfo::text).toList(),
          lateral.tableAlias(),
          lateral.columnAliases());
    for (var join : r.joins()) {
      System.out.println(indent + "JOIN 类型：" + join.type());
      printRelation(join.right(), indent + "  ");
      printExpression("ON", join.on(), indent + "  ");
      if (!join.usingColumns().isEmpty())
        System.out.println(indent + "  USING：" + join.usingColumns());
      printExpression("MATCH_CONDITION", join.matchCondition(), indent + "  ");
      for (var hint : join.hints()) System.out.println(indent + "  JOIN hint：" + hint.text());
    }
  }

  private static void printExpression(String title, ExpressionInfo e, String indent) {
    if (e == null) return;
    System.out.println(indent + title + "：" + e.text());
    System.out.println(
        indent
            + "  引用名称（未绑定物理列）："
            + e.columnReferences().stream().map(ColumnReference::parts).toList());
    for (var window : e.syntax().descendants("windowSpec"))
      System.out.println(indent + "  窗口：" + window.text());
    for (var sub : e.subqueries()) {
      System.out.println(indent + "  表达式子查询：");
      printQuery(sub, indent + "    ");
    }
  }

  private static void printTree(SyntaxNode node, String indent) {
    System.out.printf(
        "%s%s/%s [%d,%d) labels=%s text=%s%n",
        indent,
        node.rule(),
        node.kind(),
        node.span().startOffset(),
        node.span().endOffset(),
        node.labels(),
        escaped(node.text()));
    for (var child : node.children()) printTree(child, indent + "  ");
  }

  private static String escaped(String text) {
    return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  private static String argument(String[] args, int index) {
    if (index >= args.length) throw new IllegalArgumentException("参数缺少值");
    return args[index];
  }

  private static void help() {
    System.out.println(
        """
        直接运行 DorisSqlParserTestMain.main() 即可打印全部示例。
        可选参数：
          --version 2.1|4.0|all     默认 all
          --category DDL|DML|QUERY|CTE|ADVANCED|COMMAND|VERSION|INVALID|API
          --list                   查看示例目录
          --tree                   额外打印完整语法树（输出较多）
          --sql "SELECT ..."       解析自己的 SQL 或多语句脚本
        在 samples() 的分组方法中添加 sql(...) 即可扩展示例。
        """);
  }

  private static void sql(List<Sample> cases, String category, String name, String sql) {
    cases.add(new Sample(category, name, sql, Mode.STATEMENT));
  }

  private static List<Sample> samples() {
    List<Sample> cases = new ArrayList<>();
    ddl(cases);
    dml(cases);
    queries(cases);
    ctes(cases);
    advanced(cases);
    commands(cases);
    versions(cases);
    invalid(cases);
    api(cases);
    return List.copyOf(cases);
  }

  // DDL 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void ddl(List<Sample> cases) {
    sql(cases, "DDL", "简单建表", "CREATE TABLE users (id BIGINT, name VARCHAR(100))");
    sql(
        cases,
        "DDL",
        "日志表：列注释、排序键、RANGE分区、分桶、属性",
        """
        CREATE TABLE IF NOT EXISTS user_log (
          log_id BIGINT NOT NULL COMMENT '日志ID',
          user_id BIGINT NOT NULL COMMENT '用户ID',
          event_time DATETIME NOT NULL COMMENT '发生时间',
          action VARCHAR(50) COMMENT '行为', ip_addr STRING
        ) DUPLICATE KEY(log_id, user_id, event_time)
        COMMENT '用户行为日志'
        PARTITION BY RANGE(event_time) (
          PARTITION p20231001 VALUES LESS THAN ('2023-10-02'),
          PARTITION p20231002 VALUES LESS THAN ('2023-10-03')
        )
        DISTRIBUTED BY HASH(user_id) BUCKETS 10
        PROPERTIES ('replication_num'='1', 'compression'='ZSTD')
        """);
    sql(
        cases,
        "DDL",
        "UNIQUE KEY和CLUSTER BY",
        "CREATE TABLE user_profile (id BIGINT, city STRING, score INT) UNIQUE KEY(id) CLUSTER"
            + " BY(city, id) DISTRIBUTED BY HASH(id) BUCKETS 8 PROPERTIES"
            + " ('enable_unique_key_merge_on_write'='true')");
    sql(
        cases,
        "DDL",
        "AGGREGATE KEY和聚合列",
        "CREATE TABLE sales_total (user_id BIGINT, amount DECIMAL(18,2) SUM, last_time DATETIME"
            + " MAX) AGGREGATE KEY(user_id) DISTRIBUTED BY HASH(user_id) BUCKETS 4");
    sql(
        cases,
        "DDL",
        "多列LIST分区",
        "CREATE TABLE region_sales (country STRING, city STRING, amount INT) PARTITION BY"
            + " LIST(country,city) (PARTITION p_cn VALUES IN (('CN','Beijing'),('CN','Shanghai')),"
            + " PARTITION p_us VALUES IN (('US','NewYork'))) DISTRIBUTED BY RANDOM BUCKETS AUTO");
    sql(
        cases,
        "DDL",
        "固定RANGE区间和MAXVALUE",
        "CREATE TABLE age_group (age INT) PARTITION BY RANGE(age) (PARTITION p0 VALUES [(0),(18)),"
            + " PARTITION p1 VALUES [(18),(60)), PARTITION pmax VALUES LESS THAN MAXVALUE)");
    sql(
        cases,
        "DDL",
        "批量日期分区STEP",
        "CREATE TABLE daily_log (dt DATE, id BIGINT) PARTITION BY RANGE(dt) (FROM ('2023-01-01') TO"
            + " ('2024-01-01') INTERVAL 1 MONTH)");
    sql(
        cases,
        "DDL",
        "AUTO自动分区",
        "CREATE TABLE events (event_time DATETIME, id BIGINT) AUTO PARTITION BY"
            + " RANGE(date_trunc(event_time,'day')) () DISTRIBUTED BY HASH(id) BUCKETS AUTO");
    sql(
        cases,
        "DDL",
        "索引与ROLLUP",
        "CREATE TABLE search_log (id BIGINT, content STRING, INDEX idx_content(content) USING"
            + " INVERTED PROPERTIES ('parser'='english') COMMENT '全文索引') DUPLICATE KEY(id)"
            + " DISTRIBUTED BY HASH(id) BUCKETS 4 ROLLUP (r_id(id))");
    sql(
        cases,
        "DDL",
        "ARRAY、MAP、STRUCT复杂类型",
        "CREATE TABLE complex_data (id BIGINT, tags ARRAY<STRING>, attrs MAP<STRING,STRING>,"
            + " profile STRUCT<age:INT,city:STRING>)");
    sql(
        cases,
        "DDL",
        "默认值、自增、ON UPDATE",
        "CREATE TABLE auto_log (id BIGINT NOT NULL AUTO_INCREMENT(100), created_at DATETIME DEFAULT"
            + " CURRENT_TIMESTAMP(3), updated_at DATETIME DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE"
            + " CURRENT_TIMESTAMP(3), status INT DEFAULT 0 COMMENT '状态')");
    sql(
        cases,
        "DDL",
        "CREATE TABLE AS SELECT",
        "CREATE TABLE daily_sales DISTRIBUTED BY HASH(user_id) BUCKETS 4 AS SELECT user_id,"
            + " SUM(amount) AS total FROM orders GROUP BY user_id");
    sql(
        cases,
        "DDL",
        "CTAS显式列名",
        "CREATE TABLE user_copy (uid, uname) AS SELECT id, name FROM users");
    sql(cases, "DDL", "CREATE TABLE LIKE", "CREATE TABLE users_backup LIKE app.users");
    sql(
        cases,
        "DDL",
        "CREATE VIEW",
        "CREATE VIEW active_users (uid COMMENT '用户ID', uname COMMENT '用户名') AS SELECT id, name FROM"
            + " users WHERE status=1");
    sql(
        cases,
        "DDL",
        "CREATE MATERIALIZED VIEW",
        "CREATE MATERIALIZED VIEW user_order_count AS SELECT user_id, COUNT(*) AS cnt FROM orders"
            + " GROUP BY user_id");
    sql(cases, "DDL", "ALTER增加列", "ALTER TABLE users ADD COLUMN email VARCHAR(200) COMMENT '邮箱'");
    sql(cases, "DDL", "ALTER修改类型", "ALTER TABLE users MODIFY COLUMN name VARCHAR(200)");
    sql(cases, "DDL", "ALTER删除列", "ALTER TABLE users DROP COLUMN old_field");
    sql(cases, "DDL", "ALTER重命名列", "ALTER TABLE users RENAME COLUMN name user_name");
    sql(cases, "DDL", "ALTER重命名表", "ALTER TABLE users RENAME users_v2");
    sql(
        cases,
        "DDL",
        "ALTER同一语句多个动作",
        "ALTER TABLE users ADD COLUMN age INT, DROP COLUMN obsolete");
    sql(cases, "DDL", "ALTER修改属性", "ALTER TABLE users SET ('replication_num'='1')");
    sql(
        cases,
        "DDL",
        "ALTER增加分区",
        "ALTER TABLE daily_log ADD PARTITION p202401 VALUES LESS THAN ('2024-02-01')");
    sql(cases, "DDL", "ALTER删除分区", "ALTER TABLE daily_log DROP PARTITION p202301");
    sql(
        cases,
        "DDL",
        "ALTER VIEW修改查询",
        "ALTER VIEW active_users AS SELECT id, name FROM users WHERE status=2");
    sql(cases, "DDL", "DROP TABLE删除表", "DROP TABLE IF EXISTS users_backup");
    sql(cases, "DDL", "DROP VIEW删除视图", "DROP VIEW IF EXISTS active_users");
    sql(cases, "DDL", "TRUNCATE清空表", "TRUNCATE TABLE user_log");
  }

  // DML 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void dml(List<Sample> cases) {
    sql(cases, "DML", "UPDATE单表", "UPDATE users SET name='张三', age=30 WHERE id=1");
    sql(
        cases,
        "DML",
        "UPDATE FROM关联更新",
        "UPDATE users u SET city=s.city FROM user_staging s WHERE u.id=s.id");
    sql(cases, "DML", "DELETE按条件删除", "DELETE FROM user_log WHERE event_time < '2023-01-01'");
    sql(
        cases,
        "DML",
        "DELETE USING关联删除",
        "DELETE FROM users USING disabled_users d WHERE users.id=d.id");
    sql(
        cases,
        "DML",
        "INSERT INTO多行VALUES",
        "INSERT INTO users (id,name) VALUES (1,'Alice'),(2,'Bob'),(3,'张三')");
    sql(
        cases,
        "DML",
        "INSERT NULL与DEFAULT",
        "INSERT INTO users (id,name,age) VALUES (1,NULL,DEFAULT),(2,'Bob',20)");
    sql(
        cases,
        "DML",
        "INSERT INTO SELECT显式列对应",
        "INSERT INTO users_backup(id,name) SELECT id,name FROM users WHERE status=1");
    sql(
        cases,
        "DML",
        "INSERT带AS和计算表达式",
        "INSERT INTO daily_sales(user_id,total) SELECT o.user_id AS uid,SUM(o.amount*o.quantity) AS"
            + " total_amount FROM orders AS o GROUP BY o.user_id");
    sql(cases, "DML", "INSERT未写目标列：打印未解析原因", "INSERT INTO users_backup SELECT id,name FROM users");
    sql(
        cases,
        "DML",
        "INSERT SELECT星号：打印未解析原因",
        "INSERT INTO users_backup(id,name) SELECT * FROM users");
    sql(
        cases,
        "DML",
        "INSERT OVERWRITE全表覆盖",
        "INSERT OVERWRITE TABLE users_backup(id,name) SELECT id,name FROM users WHERE status=1");
    sql(
        cases,
        "DML",
        "INSERT OVERWRITE指定分区",
        "INSERT OVERWRITE TABLE daily_sales PARTITION(p202401) (user_id,total) SELECT"
            + " user_id,SUM(amount) FROM orders WHERE dt='2024-01-01' GROUP BY user_id");
    sql(
        cases,
        "DML",
        "INSERT WITH LABEL",
        "INSERT INTO users WITH LABEL batch_001 (id,name) VALUES (10,'Alice')");
    sql(
        cases,
        "DML",
        "INSERT UNION多个来源",
        "INSERT INTO user_ids(id) SELECT id FROM app_users UNION ALL SELECT user_id FROM"
            + " web_users");
    sql(
        cases,
        "DML",
        "INSERT VALUES标量子查询",
        "INSERT INTO user_count(cnt) VALUES ((SELECT COUNT(*) FROM users))");
    sql(
        cases,
        "DML",
        "EXPLAIN INSERT：不会执行写入",
        "EXPLAIN INSERT INTO users_backup(id) SELECT id FROM users");
  }

  // QUERY 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void queries(List<Sample> cases) {
    sql(cases, "QUERY", "常量查询", "SELECT 1 AS one,'hello' AS greeting,NULL AS empty_value");
    sql(cases, "QUERY", "单表列查询", "SELECT id,name,age FROM users");
    sql(
        cases,
        "QUERY",
        "单表条件与分页",
        "SELECT id,name FROM users WHERE age>=18 AND status=1 ORDER BY id DESC LIMIT 10 OFFSET 20");
    sql(cases, "QUERY", "单表星号", "SELECT * FROM users");
    sql(cases, "QUERY", "表名前缀星号", "SELECT u.* FROM users AS u");
    sql(
        cases,
        "QUERY",
        "列AS与中文别名",
        "SELECT u.id AS user_id,u.name AS `用户名称`,u.age+1 AS next_age FROM users AS u");
    sql(cases, "QUERY", "省略AS的别名", "SELECT u.id uid,u.name uname FROM users u");
    sql(
        cases,
        "QUERY",
        "反引号标识符中含点",
        "SELECT t.`a.b` AS `结果.列` FROM `db.with.dot`.`table.with.dot` AS t");
    sql(
        cases,
        "QUERY",
        "INNER JOIN",
        "SELECT u.id,u.name,o.amount FROM users u INNER JOIN orders o ON u.id=o.user_id");
    sql(
        cases,
        "QUERY",
        "LEFT JOIN复合ON条件",
        "SELECT u.id,o.order_id FROM users u LEFT JOIN orders o ON u.id=o.user_id AND o.status=1");
    sql(
        cases,
        "QUERY",
        "RIGHT JOIN",
        "SELECT u.id,o.order_id FROM users u RIGHT JOIN orders o ON u.id=o.user_id");
    sql(
        cases,
        "QUERY",
        "FULL OUTER JOIN",
        "SELECT u.id,o.order_id FROM users u FULL OUTER JOIN orders o ON u.id=o.user_id");
    sql(cases, "QUERY", "CROSS JOIN", "SELECT u.id,d.dt FROM users u CROSS JOIN calendar_dim d");
    sql(
        cases,
        "QUERY",
        "USING关联",
        "SELECT user_id,amount FROM user_dim JOIN order_fact USING(user_id)");
    sql(
        cases,
        "QUERY",
        "三表关联",
        "SELECT u.name,o.order_id,p.product_name FROM users u JOIN orders o ON u.id=o.user_id LEFT"
            + " JOIN products p ON o.product_id=p.id");
    sql(
        cases,
        "QUERY",
        "自关联及不同别名",
        "SELECT e.name AS employee,m.name AS manager FROM employees e LEFT JOIN employees m ON"
            + " e.manager_id=m.id");
    sql(
        cases,
        "QUERY",
        "逗号多表查询",
        "SELECT u.id,o.order_id FROM users u,orders o WHERE u.id=o.user_id");
    sql(
        cases,
        "QUERY",
        "LEFT SEMI JOIN",
        "SELECT u.id FROM users u LEFT SEMI JOIN orders o ON u.id=o.user_id");
    sql(
        cases,
        "QUERY",
        "LEFT ANTI JOIN",
        "SELECT u.id FROM users u LEFT ANTI JOIN orders o ON u.id=o.user_id");
    sql(cases, "QUERY", "DISTINCT去重", "SELECT DISTINCT city,status FROM users");
    sql(
        cases,
        "QUERY",
        "聚合与HAVING",
        "SELECT user_id,COUNT(*) AS cnt,SUM(amount) AS total,AVG(amount) AS avg_amount FROM orders"
            + " GROUP BY user_id HAVING SUM(amount)>100 ORDER BY total DESC");
    sql(
        cases,
        "QUERY",
        "字符串函数",
        "SELECT LOWER(name) AS lower_name,UPPER(city) AS upper_city,CONCAT(name,'-',city) AS"
            + " label,SUBSTRING(name,1,2) AS short_name FROM users");
    sql(
        cases,
        "QUERY",
        "数值函数与空值处理",
        "SELECT ABS(amount) AS a,ROUND(amount,2) AS b,COALESCE(discount,0) AS"
            + " discount_value,IFNULL(tax,0) AS tax_value FROM orders");
    sql(
        cases,
        "QUERY",
        "日期函数",
        "SELECT DATE_ADD(dt,INTERVAL 1 DAY) AS tomorrow,DATE_SUB(dt,INTERVAL 7 DAY) AS"
            + " last_week,YEAR(dt) AS y,DATE_FORMAT(dt,'%Y-%m') AS month_text FROM orders");
    sql(
        cases,
        "QUERY",
        "CAST类型转换",
        "SELECT CAST(id AS STRING) AS id_text,CAST(amount AS DECIMAL(18,2)) AS amount_decimal FROM"
            + " orders");
    sql(
        cases,
        "QUERY",
        "CASE WHEN",
        "SELECT id,CASE WHEN age<18 THEN '未成年' WHEN age<60 THEN '成年' ELSE '老年' END AS age_group"
            + " FROM users");
    sql(
        cases,
        "QUERY",
        "IN/BETWEEN/LIKE/IS NULL条件",
        "SELECT id FROM users WHERE city IN ('北京','上海') AND age BETWEEN 18 AND 60 AND name LIKE"
            + " '张%' AND deleted_at IS NULL");
    sql(
        cases,
        "QUERY",
        "NOT、OR与正则",
        "SELECT id FROM users WHERE NOT(status=0 OR age<18) AND name REGEXP '^[A-Z]'");
    sql(cases, "QUERY", "同名投影与重复表达式", "SELECT id AS x,id AS x,name AS x FROM users");
    sql(cases, "QUERY", "LIMIT逗号写法", "SELECT id FROM users ORDER BY id LIMIT 20,10");
  }

  // CTE 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void ctes(List<Sample> cases) {
    sql(
        cases,
        "CTE",
        "单个WITH/CTE",
        "WITH active AS (SELECT id,name FROM users WHERE status=1) SELECT id,name FROM active");
    sql(
        cases,
        "CTE",
        "多个CTE依次引用",
        "WITH a AS (SELECT id FROM users),b AS (SELECT user_id,SUM(amount) total FROM orders GROUP"
            + " BY user_id) SELECT a.id,b.total FROM a JOIN b ON a.id=b.user_id");
    sql(
        cases,
        "CTE",
        "CTE显式列别名",
        "WITH c(uid,uname) AS (SELECT id,name FROM users) SELECT c.uid AS id,c.uname AS name FROM"
            + " c");
    sql(
        cases,
        "CTE",
        "CTE自关联",
        "WITH c AS (SELECT id,manager_id FROM employees) SELECT e.id,m.id AS manager FROM c e LEFT"
            + " JOIN c m ON e.manager_id=m.id");
    sql(
        cases,
        "CTE",
        "WITH置于INSERT之前",
        "WITH active AS (SELECT id,name FROM users WHERE status=1) INSERT INTO"
            + " users_backup(id,name) SELECT id,name FROM active");
    sql(
        cases,
        "CTE",
        "INSERT之后的查询使用WITH",
        "INSERT INTO users_backup(id,name) WITH active AS (SELECT id,name FROM users WHERE"
            + " status=1) SELECT id,name FROM active");
    sql(
        cases,
        "CTE",
        "FROM派生表",
        "SELECT x.user_id,x.total FROM (SELECT user_id,SUM(amount) AS total FROM orders GROUP BY"
            + " user_id) AS x WHERE x.total>100");
    sql(
        cases,
        "CTE",
        "SELECT标量子查询",
        "SELECT u.id,(SELECT MAX(amount) FROM orders) AS max_amount FROM users u");
    sql(
        cases,
        "CTE",
        "IN子查询",
        "SELECT id,name FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount>100)");
    sql(
        cases,
        "CTE",
        "EXISTS相关子查询",
        "SELECT u.id FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id=u.id AND"
            + " o.amount>100)");
    sql(
        cases,
        "CTE",
        "NOT EXISTS相关子查询",
        "SELECT u.id FROM users u WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id=u.id)");
    sql(
        cases,
        "CTE",
        "相关标量子查询",
        "SELECT u.id,(SELECT COUNT(*) FROM orders o WHERE o.user_id=u.id) AS order_count FROM users"
            + " u");
    sql(
        cases,
        "CTE",
        "三层嵌套每层AS",
        """
        SELECT level3.uid AS final_user_id,level3.total AS final_total
        FROM (
          SELECT level2.uid,level2.total
          FROM (
            SELECT level1.user_id AS uid,SUM(level1.amount) AS total
            FROM (SELECT o.user_id,o.amount FROM orders AS o WHERE o.status=1) AS level1
            GROUP BY level1.user_id
          ) AS level2
          WHERE level2.total>100
        ) AS level3
        ORDER BY final_total DESC
        """);
    sql(
        cases,
        "CTE",
        "两个派生表关联",
        "SELECT a.uid,b.total FROM (SELECT id AS uid FROM users WHERE status=1) AS a JOIN (SELECT"
            + " user_id AS uid,SUM(amount) AS total FROM orders GROUP BY user_id) AS b ON"
            + " a.uid=b.uid");
    sql(
        cases,
        "CTE",
        "嵌套WITH同名遮蔽",
        "WITH c AS (SELECT id FROM base_users) SELECT x.id FROM (WITH c AS (SELECT id FROM c WHERE"
            + " id>10) SELECT id FROM c) AS x");
    sql(
        cases,
        "CTE",
        "CTE内部UNION",
        "WITH ids AS (SELECT id FROM app_users UNION ALL SELECT user_id AS id FROM web_users)"
            + " SELECT DISTINCT id FROM ids");
    sql(
        cases,
        "CTE",
        "WITH加窗口加外层过滤",
        """
        WITH totals AS (
          SELECT user_id,SUM(amount) AS total FROM orders GROUP BY user_id
        ), ranked AS (
          SELECT user_id,total,ROW_NUMBER() OVER(ORDER BY total DESC) AS rn FROM totals
        )
        SELECT r.user_id AS uid,r.total FROM ranked AS r WHERE r.rn<=10
        """);
    sql(
        cases,
        "CTE",
        "WITH加多层子查询再OVERWRITE",
        """
        WITH paid AS (
          SELECT user_id,amount FROM orders WHERE status=1
        ), totals AS (
          SELECT user_id,SUM(amount) AS total FROM paid GROUP BY user_id
        )
        INSERT OVERWRITE TABLE user_report(user_id,total)
        SELECT result.uid,result.total
        FROM (
          SELECT t.user_id AS uid,t.total FROM totals AS t
          JOIN (SELECT id FROM users WHERE status=1) AS u ON t.user_id=u.id
        ) AS result WHERE result.total>100
        """);
  }

  // ADVANCED 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void advanced(List<Sample> cases) {
    sql(
        cases,
        "ADVANCED",
        "ROW_NUMBER与RANK窗口",
        "SELECT user_id,amount,ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY amount DESC) AS"
            + " rn,RANK() OVER(ORDER BY amount DESC) AS ranking FROM orders");
    sql(
        cases,
        "ADVANCED",
        "窗口聚合和ROWS范围",
        "SELECT user_id,dt,SUM(amount) OVER(PARTITION BY user_id ORDER BY dt ROWS BETWEEN 2"
            + " PRECEDING AND CURRENT ROW) AS moving_total FROM orders");
    sql(
        cases,
        "ADVANCED",
        "LAG/LEAD窗口",
        "SELECT user_id,dt,LAG(amount,1,0) OVER(PARTITION BY user_id ORDER BY dt) AS"
            + " previous_amount,LEAD(amount) OVER(PARTITION BY user_id ORDER BY dt) AS next_amount"
            + " FROM orders");
    sql(
        cases,
        "ADVANCED",
        "UNION ALL",
        "SELECT id AS uid FROM app_users UNION ALL SELECT user_id AS uid FROM web_users");
    sql(
        cases,
        "ADVANCED",
        "UNION去重",
        "SELECT id FROM app_users UNION SELECT user_id FROM web_users");
    sql(
        cases,
        "ADVANCED",
        "INTERSECT和EXCEPT",
        "SELECT id FROM app_users INTERSECT SELECT user_id FROM web_users EXCEPT SELECT id FROM"
            + " disabled_users");
    sql(
        cases,
        "ADVANCED",
        "括号集合分支各自分页",
        "(SELECT id FROM app_users ORDER BY id LIMIT 5) UNION ALL (SELECT user_id FROM web_users"
            + " ORDER BY user_id LIMIT 5)");
    sql(
        cases,
        "ADVANCED",
        "旧式UNION尾部LIMIT归属",
        "SELECT id FROM app_users UNION ALL SELECT user_id FROM web_users LIMIT 10");
    sql(
        cases,
        "ADVANCED",
        "GROUPING SETS",
        "SELECT city,status,SUM(amount) AS total FROM orders GROUP BY GROUPING SETS"
            + " ((city,status),(city),())");
    sql(
        cases,
        "ADVANCED",
        "ROLLUP",
        "SELECT city,status,SUM(amount) AS total FROM orders GROUP BY ROLLUP(city,status)");
    sql(
        cases,
        "ADVANCED",
        "CUBE",
        "SELECT city,status,SUM(amount) AS total FROM orders GROUP BY CUBE(city,status)");
    sql(
        cases,
        "ADVANCED",
        "ARRAY及lambda局部参数",
        "SELECT id,array_map(x -> X+1,numbers) AS incremented,array_filter(x -> x>0,numbers) AS"
            + " positive FROM array_data");
    sql(
        cases,
        "ADVANCED",
        "JSON函数",
        "SELECT JSON_EXTRACT(payload,'$.user.id') AS user_id,JSON_EXTRACT_STRING(payload,'$.city')"
            + " AS city FROM raw_events");
    sql(
        cases,
        "ADVANCED",
        "LATERAL VIEW展开数组",
        "SELECT t.id,lv.tag FROM tag_table t LATERAL VIEW explode(tags) lv AS tag");
    sql(cases, "ADVANCED", "表值函数TVF", "SELECT number FROM numbers(\"number\"=\"5\")");
    sql(
        cases,
        "ADVANCED",
        "表分区选择",
        "SELECT user_id,amount FROM daily_sales PARTITION(p202401,p202402)");
    sql(
        cases,
        "ADVANCED",
        "TABLESAMPLE采样",
        "SELECT id,name FROM users TABLESAMPLE(10 PERCENT) REPEATABLE 7");
    sql(
        cases,
        "ADVANCED",
        "JOIN分发hint",
        "SELECT u.id,o.order_id FROM users u JOIN [broadcast] orders o ON u.id=o.user_id");
    sql(
        cases,
        "ADVANCED",
        "括号关联组",
        "SELECT a.id,b.amount FROM (users a JOIN orders b ON a.id=b.user_id)");
    sql(
        cases,
        "ADVANCED",
        "INTO OUTFILE选项",
        "SELECT id,name FROM users INTO OUTFILE 'file:///tmp/users.csv' FORMAT AS CSV PROPERTIES"
            + " ('column_separator'=',')");
    sql(
        cases,
        "ADVANCED",
        "EXPLAIN SELECT",
        "EXPLAIN SELECT u.id,COUNT(*) AS cnt FROM users u JOIN orders o ON u.id=o.user_id GROUP BY"
            + " u.id");
    sql(cases, "ADVANCED", "超出Long的LIMIT原文", "SELECT id FROM users LIMIT 9223372036854775808");
    sql(cases, "ADVANCED", "排除部分列的星号", "SELECT * EXCEPT (password,secret) FROM users");
  }

  // COMMAND 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void commands(List<Sample> cases) {
    sql(cases, "COMMAND", "创建数据库", "CREATE DATABASE IF NOT EXISTS demo_db");
    sql(cases, "COMMAND", "删除数据库", "DROP DATABASE IF EXISTS demo_db");
    sql(cases, "COMMAND", "切换数据库", "USE demo_db");
    sql(cases, "COMMAND", "SHOW TABLES", "SHOW TABLES FROM demo_db");
    sql(cases, "COMMAND", "SHOW CREATE TABLE", "SHOW CREATE TABLE users");
    sql(cases, "COMMAND", "DESCRIBE表结构", "DESC users");
    sql(cases, "COMMAND", "SET会话参数", "SET query_timeout=30");
    sql(cases, "COMMAND", "ANALYZE统计信息", "ANALYZE TABLE users");
    sql(
        cases,
        "COMMAND",
        "LOAD显式表来源",
        "LOAD LABEL demo.batch_001 (DATA FROM TABLE source_users INTO TABLE target_users)");
    sql(
        cases,
        "COMMAND",
        "EXPORT导出语法",
        "EXPORT TABLE users TO 'file:///tmp/users/' PROPERTIES ('format'='csv')");
  }

  // VERSION 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void versions(List<Sample> cases) {
    sql(cases, "VERSION", "ANALYZER标识符：2.1可用、4.0保留字", "SELECT ANALYZER FROM t");
    sql(
        cases,
        "VERSION",
        "TRY_CAST：4.0语法",
        "SELECT TRY_CAST(value AS INT) AS int_value FROM raw_data");
    sql(cases, "VERSION", "STOP SYNC JOB：2.1旧语法", "STOP SYNC JOB sync_users");
    sql(cases, "VERSION", "生成列：4.0语法", "CREATE TABLE generated_data (a INT,b INT AS (a+1))");
    sql(
        cases,
        "VERSION",
        "QUALIFY：4.0语法",
        "SELECT id,ROW_NUMBER() OVER(ORDER BY score DESC) AS rn FROM users QUALIFY rn<=10");
    sql(cases, "VERSION", "临时表：4.0语法", "CREATE TEMPORARY TABLE tmp_users (id BIGINT,name STRING)");
    sql(
        cases,
        "VERSION",
        "MERGE INTO：4.0语法",
        "MERGE INTO users t USING user_staging s ON t.id=s.id WHEN MATCHED THEN UPDATE SET"
            + " name=s.name WHEN NOT MATCHED THEN INSERT(id,name) VALUES(s.id,s.name)");
  }

  // INVALID 示例：可继续在此添加 sql(cases, 分类, 名称, SQL)。
  private static void invalid(List<Sample> cases) {
    sql(cases, "INVALID", "缺少SELECT表达式（故意错误）", "SELECT FROM users");
    sql(cases, "INVALID", "未闭合字符串（故意错误）", "SELECT 'not closed");
    sql(cases, "INVALID", "未闭合括号（故意错误）", "SELECT id FROM (SELECT id FROM users");
    sql(cases, "INVALID", "未加反引号的连字符标识符（故意错误）", "SELECT id FROM user-log");
    sql(
        cases,
        "INVALID",
        "OVERWRITE拼写错误（故意错误）",
        "INSERT OVERWIRTE TABLE users SELECT id FROM users");
    sql(cases, "INVALID", "未闭合注释（故意错误）", "SELECT 1 /* comment");
    sql(cases, "INVALID", "多条SQL传给单语句入口（故意错误）", "SELECT 1; SELECT 2;");
    sql(cases, "INVALID", "空SQL传给单语句入口（故意错误）", "");
  }

  private static void api(List<Sample> cases) {
    cases.add(
        new Sample(
            "API",
            "多语句混合脚本",
            "CREATE TABLE t(id INT); INSERT INTO t(id) VALUES(1); SELECT id FROM t; DROP TABLE t;",
            Mode.SCRIPT));
    cases.add(
        new Sample(
            "API",
            "字符串中的分号和前后注释",
            "-- 开头\nSELECT ';' AS marker; /* 中间 */ SELECT '😀' AS emoji; -- 结尾\n",
            Mode.SCRIPT));
    cases.add(new Sample("API", "纯注释脚本", "-- 只有注释\n/* 没有语句 */", Mode.SCRIPT));
    cases.add(
        new Sample("API", "数学与函数表达式", "COALESCE(price,0) * quantity - discount", Mode.EXPRESSION));
    cases.add(
        new Sample(
            "API", "CASE表达式", "CASE WHEN amount>100 THEN 'high' ELSE 'low' END", Mode.EXPRESSION));
  }
}
