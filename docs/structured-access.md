# SQL 各部分怎么获取（0.2.0 / JDK 17）

解析一次后，先按语句类型读取：

```java
var parser = new DorisSqlParser(DorisVersion.DORIS_2_1);
var result = parser.parseStatement(sql);
var ddl = result.tableDefinition(); // 建表、视图等；其他语句为 null
var query = result.query();         // SELECT、INSERT SELECT/VALUES、CTAS 等的查询
var insert = result.insert();      // INSERT 信息；其他语句为 null
var syntax = result.syntax();      // 所有成功解析的语句都有，可继续遍历全部语法
```

下面字段均为 Java record 访问器，无 `get` 前缀。未声明的单值返回 `null`，没有元素的集合返回空集合。列表顺序与 SQL 声明顺序一致；属性 Map 不保证迭代顺序，使用 `propertyItems()` 保留声明顺序及重复键。

## 1. CREATE TABLE：列、注释、分区、分桶、排序、属性

```java
var table = result.tableDefinition();
for (var col : table.columns()) {
    System.out.println(col.name());                // log_id
    System.out.println(col.dataType());            // BIGINT
    System.out.println(col.comment());             // 日志ID，SQL 未写 COMMENT 时为 null
    System.out.println(col.nullable());            // 显式 NOT NULL => false；未声明 => null
    System.out.println(col.defaultExpression());   // 原始默认表达式
    System.out.println(col.aggregateType());       // SUM / REPLACE 等
    System.out.println(col.generatedExpression()); // 4.0 生成列表达式
    System.out.println(col.autoIncrementStart());  // AUTO_INCREMENT(100) => "100"
    System.out.println(col.onUpdateExpression());  // CURRENT_TIMESTAMP(3)
    System.out.println(col.typeSyntax());          // ARRAY/MAP/STRUCT 等类型结构树
}

System.out.println(table.keyType());     // DUPLICATE / UNIQUE / AGGREGATE
System.out.println(table.keyColumns());  // 声明的 KEY 列
System.out.println(table.clusterKeys()); // 声明的 CLUSTER BY 列
System.out.println(table.sortColumns()); // 存在 CLUSTER BY 时取它，否则取 KEY 列
System.out.println(table.comment());     // 表注释
System.out.println(table.properties().get("replication_num"));
System.out.println(table.propertyItems());      // 有序，保留重复 key 和原始 key/value SQL
System.out.println(table.externalProperties()); // BROKER PROPERTIES
System.out.println(table.indexes());            // 索引名、列、类型、注释、properties
System.out.println(table.rollups());            // 名称、列、DUPLICATE KEY、properties
System.out.println(table.ctasColumns());        // CTAS 显式输出列名
```

这里只报告 SQL 声明；`sortColumns()` 不模拟 Doris 自动补列或物理存储布局。

```java
var partition = table.partition();
if (partition != null) {
    System.out.println(partition.type());        // RANGE / LIST / MATERIALIZED_VIEW
    System.out.println(partition.automatic());   // AUTO PARTITION
    System.out.println(partition.columns());     // 简单标识符列
    System.out.println(partition.expressions()); // 包括 date_trunc(...) 等完整表达式
    for (var p : partition.partitions()) {
        System.out.println(p.name());           // p20231001；STEP 定义没有显式名称
        System.out.println(p.kind());           // LESS_THAN / FIXED_RANGE / STEP / IN
        System.out.println(p.lowerBound());
        System.out.println(p.upperBound());
        System.out.println(p.inValues());       // List<List<PartitionValue>>，保留多列元组
        System.out.println(p.intervalAmount()); // STEP 的 INTERVAL 数量
        System.out.println(p.intervalUnit());
        System.out.println(p.properties());     // 当前分区自身属性
    }
}
var distribution = table.distribution();
if (distribution != null) {
    System.out.println(distribution.type());        // HASH / RANDOM
    System.out.println(distribution.columns());     // [user_id]
    System.out.println(distribution.buckets());     // "10" / "AUTO" / null
    System.out.println(distribution.autoBuckets());
}
```

`PartitionValue` 有 `kind()`、`value()`、`sql()`。例如 `'2023-10-02'` 对应 `STRING` / `2023-10-02` / `'2023-10-02'`；SQL `NULL` 对应 `NULL` / Java null / `NULL`。不将数字转换为有限宽度整数。STEP 保留起止及间隔，不执行 Doris 的分区枚举。

## 2. SELECT：投影列、引用列、JOIN、条件

例如：

```sql
SELECT u.id AS user_id, SUM(o.amount) AS total
FROM users u LEFT JOIN orders o ON u.id = o.user_id
WHERE o.status = 1
GROUP BY u.id
HAVING SUM(o.amount) > 100
ORDER BY total DESC
LIMIT 10
```

```java
var q = parser.parseStatement(sql).query();
for (var item : q.selectItems()) {
    System.out.println(item.alias());                        // user_id / total
    System.out.println(item.expression().text());            // u.id / SUM(o.amount)
    System.out.println(item.expression().columnReferences()); // 原文列引用及分段名称
    System.out.println(item.wildcard());                     // 投影是否是 * / t.*
    System.out.println(item.expression().subqueries());       // 当前表达式中的子查询
}
var users = q.relations().get(0);
System.out.println(users.table().qualifiedName()); // `users`
System.out.println(users.alias());                 // u
var join = users.joins().get(0);
System.out.println(join.type());                   // LEFT
System.out.println(join.right().table());          // orders
System.out.println(join.right().alias());          // o
System.out.println(join.on().text());       // u.id = o.user_id
System.out.println(join.usingColumns());           // JOIN USING(id) 时读取
System.out.println(q.where().text());
System.out.println(q.groupBy().expressions());
System.out.println(q.having().text());
System.out.println(q.orderBy());
System.out.println(q.limit().count());             // "10"；不丢失超出 Long 的整数
```

**一个查询层级对应一个 QueryInfo。** 不把子查询的 WHERE、JOIN、LIMIT 混到外层：

| SQL 结构 | 访问方式 |
|---|---|
| WITH | `q.ctes()`，每项有名称、列别名、`query()` |
| UNION / INTERSECT / EXCEPT | `q.kind()` 为 `SET_OPERATION`，`q.setOperation().left()/right()` |
| 整体括号查询 | `q.kind()` 为 `PARENTHESIZED`，`q.nestedQuery()` |
| FROM 子查询 | `q.relations().get(i).subquery()` |
| FROM 括号关联组 | `relation.members()` |
| 标量/EXISTS/IN 子查询 | 对应 `ExpressionInfo.subqueries()` |
| GROUPING SETS / ROLLUP / CUBE | `q.groupBy().kind()/sets()/expressions()` |
| QUALIFY | `q.qualify()` |
| LATERAL VIEW | `relation.lateralViews()` |
| 窗口函数 OVER | `item.expression().syntax().descendants("windowSpec")`，再取 `partitionClause`、`sortItem`、`windowFrame` |
| 表采样/快照/分区/TVF 参数等 | `relation.syntax().first("sample")` 等语法规则访问 |

`ColumnReference.parts()` 保存 `["u", "id"]` 等语法分段，`qualifier()` 只是原文前缀，可能是表别名、CTE 名或 STRUCT 路径，不能直接当作已绑定的物理表。关联条件是完整表达式结构，支持复合条件；不会把任意 ON 表达式猜成一对等值主外键。

## 3. INSERT：目标列与来源表达式

```java
var s = parser.parseStatement("""
    INSERT INTO report.user_sales(user_id, total)
    SELECT u.id, SUM(o.amount)
    FROM users u JOIN orders o ON u.id=o.user_id
    GROUP BY u.id
    """);
var i = s.insert();
System.out.println(i.targetTable());
System.out.println(i.targetColumns()); // [user_id, total]
for (var mapping : i.columnMappings()) {
    System.out.println(mapping.targetColumn());
    System.out.println(mapping.sourceExpressions()); // 各 VALUES 行/集合分支对应位置的来源表达式
}
System.out.println(i.unresolvedReasons());
System.out.println(i.query()); // 与 s.query() 相同查询对象
```

`INSERT ... VALUES` 的逐行表达式通过 `i.query().valuesRows()` 读取。INSERT 前置 WITH 通过 `i.ctes()` 读取。分区、LABEL、hint 等可通过 `i.syntax()` 的规则节点/字段获取。

列映射是**显式目标列与来源位置之间的语法对应**。没有目标列名、投影有 `*`、VALUES 行或 UNION 分支宽度不一致时，`columnMappings()` 留空并给出 `unresolvedReasons()`；不会生成看似完整的假映射。没有 schema 时无法展开 `*`、判断未限定列属于哪张表、获取 SELECT 列的数据库类型或注释。这需要下一层元数据绑定。

## 4. 任何其他语法：完整结构树和源码位置

```java
var node = result.syntax();
var columns = node.descendants("columnDef"); // 当前版本 grammar 的规则名
for (var column : columns) {
    System.out.println(column.field("colName").text());
    System.out.println(column.field("type").text());
    var comment = column.field("comment");
    System.out.println(comment == null ? null : comment.text());
}
// 未专门建模的 UPDATE / ALTER / LOAD 等同样可以遍历对应规则和标签。
System.out.println(node.children()); // 按原顺序保留规则节点和终结符
System.out.println(node.labels());   // 语法标签 -> children 下标列表
```

`rule()` 是 g4 规则名；`kind()` 是该规则的具体分支名，终结符则是词法 token 名。`field("left")` / `field("right")` / `field("operator")` 可以读取带标签的表达式；`fields("...")` 读取列表标签。`descendants()` 包含自身，`first()` 返回首个匹配或 null。对当前层级进行精确处理时使用 `children()`，不要把所有后代误认为直接子句。

**保证可访问的是所选版本 grammar 产生的全部节点和 token。** 这不等于每种 SQL 都有独立 Java 专用类，也不解析字符串中的动态 SQL、注释内容或 grammar 本身当作不透明 token 的存储过程体。两套版本的规则名和分支可能不同；常用业务代码优先使用以上统一对象。

完整脚本（含前后注释、空白、分号）使用：

```java
var document = parser.parseSyntax("-- 开头\nSELECT '😀'; SELECT 2;");
System.out.println(document.source());
System.out.println(document.root());
for (var token : document.tokens()) {
    System.out.println(token.kind());
    System.out.println(token.hidden());
    System.out.println(token.span().slice(document.source()));
}
var expression = parser.parseExpressionSyntax("a + b * 2");
```

`span()` 是整个原始输入中的位置：offset 从 0 开始，左闭右开，按 **Unicode 码点**计数；行列从 1 开始。使用 `span.slice(originalInput)`，不要直接把 offset 传给 Java `String.substring`，后者按 UTF-16 单元计数。`SqlStatement.sql()` 不包括外围注释/分号；多语句的 span 仍相对于整个输入。空语法规则可以有空 text/span。`parseSyntax()` 接受空脚本，`parseStatement()` 要求恰好一条非空语句。

## 5. 兼容性与结果大小

- Maven 版本改为 `0.2.0`；继续 JDK 17。原有访问器与 0.1.x 模型构造器保留；旧构造器手工创建的对象没有新提取结果。
- `metadataStatus()` 仍表示已实现的语句摘要提取覆盖；同时检查 `query().warnings()`、`insert().unresolvedReasons()`。`COMPLETE` 不表示数据库字段绑定或全部专用 Java 模型齐全。
- 结构树、表达式及源码都保留在结果中，完整 JSON 会比 0.1.x 大。应用界面/接口只需部分信息时，选择需要的字段组装自己的 DTO；语法校验调用 `checkSqlSyntax()`。
- 本地 Maven 安装使用 Maven settings 的 `localRepository`；新项目和安装命令应使用同一份 Maven 配置。
