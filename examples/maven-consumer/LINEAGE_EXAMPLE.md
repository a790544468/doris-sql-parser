# 简洁打印：来源表、结果表、列对应和公式

直接运行 [InsertLineagePrintMain.java](src/main/java/example/InsertLineagePrintMain.java) 的 `main`。默认内置复杂 INSERT SQL，不需要传参，不连接数据库，不使用断言，不打印完整语法树。

## 使用自己的 SQL

在已引用解析器 0.2.0 的示例项目中：

```java
var statement = new io.github.dorisparser.DorisSqlParser().parseStatement(sqlText);
example.InsertLineagePrintMain.print(statement);
```

也可修改类内的 `DEFAULT_SQL`，或传入 SQL 文件路径和可选版本。项目根目录运行：

```bash
./mvnw -f examples/maven-consumer/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.InsertLineagePrintMain
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.InsertLineagePrintMain examples/complex-insert.sql 4.0
```

首次使用先按根 README 执行 `./mvnw install`。Windows 推荐 IDEA 运行，命令行 classpath 分隔符使用 `;`。

## 实际输出

2.1 与 4.0 对内置 SQL 的输出一致，共 37 行：

```text
来源表：[users, orders, refunds, order_items, user_tags, memberships]
结果表：report.user_profile
说明：按 SQL 显式来源追踪；来源列含 CASE 判断用列，不包含仅用于 JOIN/WHERE 的行筛选列。
公式按查询层级列出，不能直接拼成一层 SQL 执行。

1. 结果字段：report.user_profile.user_id
   来源字段：users.id
   公式：users.id

2. 结果字段：report.user_profile.user_name
   来源字段：users.name
   公式：x.user_name

3. 结果字段：report.user_profile.total_amount
   来源字段：orders.status, orders.amount, refunds.amount
   公式：x.total_amount

4. 结果字段：report.user_profile.user_level
   来源字段：orders.status, orders.amount, refunds.amount, memberships.user_id
   公式：CASE WHEN x.total_amount >= 1000 AND memberships.user_id IS NOT NULL THEN 'VIP' WHEN x.total_amount > 0 THEN 'ACTIVE' ELSE 'NEW' END

5. 结果字段：report.user_profile.last_order_time
   来源字段：orders.created_at
   公式：latest.last_order_time

计算过程（中间名称按子查询路径区分，重复步骤只打印一次）：
  x.user_name = COALESCE(users.name, 'unknown')
  分组（x.s.detail.r）：GROUP BY order_id
  x.s.detail.r.refund_amount = SUM(refunds.amount)
  x.s.detail.net_amount = CASE WHEN orders.status = 'PAID' THEN orders.amount - COALESCE(x.s.detail.r.refund_amount, 0) ELSE 0 END
  分组（x.s）：GROUP BY detail.user_id
  x.s.total_amount = SUM(x.s.detail.net_amount)
  x.total_amount = COALESCE(x.s.total_amount, 0)
  分组（latest）：GROUP BY user_id
  latest.last_order_time = MAX(orders.created_at)

共 5 个结果字段。JOIN、WHERE、EXISTS、IN 还会影响行是否保留及聚合结果。
```

## 范围

这是基于解析器结构化结果编写的**示例级静态列来源追踪**，代码独立放在示例项目，不修改核心库的 `columnMappings()`。它能沿显式子查询/非递归 CTE、AS、JOIN 的限定列追踪来源，展开输出已知的派生表星号，并保留公式链。CASE 条件引用也计入来源字段。

- 物理表 `*`、多表未限定列歧义、重复名称引用、目标列缺失或数量不一致会明确提示，不猜测。
- 集合查询、值表达式中的标量/相关子查询、窗口函数、LATERAL VIEW、USING/NATURAL/SEMI/ANTI JOIN 等不在此简洁追踪示例范围内，原解析器仍可解析其受支持语法。
- 不做数据库 schema 校验、复杂字段路径绑定、递归/前向 CTE 绑定，也不模拟数据库标识符大小写规则；列来源依赖 SQL 中明确的名称和限定关系。
- JOIN/WHERE/HAVING/EXISTS/IN、分组与排序等会影响结果行和聚合。这里的逐列来源是值表达式及 CASE 判断的依赖，不是完整的行依赖/控制依赖血缘。分组原文作为公式步骤的上下文显示。
- `x.s.detail.net_amount` 是示例为区分嵌套层级生成的中间名称；公式链用于说明计算过程，不应拼成一层 SQL 直接执行。

已用内置复杂 SQL 双版本、CTE 列别名、连续派生表星号、Unicode/字符串空格、常量/COUNT(*)，以及歧义列、物理表星号、重复列、集合查询、标量子查询和列数不一致等输入检查打印行为。
