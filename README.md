# Doris SQL Parser for Java

独立、纯 Java 的 Apache Doris SQL 解析工具。输入 SQL，获取语句类型、读写表、列定义与注释、分区分桶、属性、查询列、关联条件、CTE、嵌套查询、INSERT 列映射，以及完整语法结构树。

**Java 17+ · 默认 Doris 2.1 · 可选 Doris 4.0 · 无需连接 Doris**

## 目录

- [打包与安装](#打包与安装)
- [新 Maven 项目如何引用](#新-maven-项目如何引用)
- [第一个 Java main](#第一个-java-main)
- [解析器有哪些方法](#解析器有哪些方法)
- [结果从哪里读取](#结果从哪里读取)
- [建表列注释分区分桶和属性](#建表列注释分区分桶和属性)
- [查询列函数别名和关联](#查询列函数别名和关联)
- [WITH 和多层嵌套](#with-和多层嵌套)
- [INSERT INTO 和 OVERWRITE](#insert-into-和-overwrite)
- [修改删除和其他语句](#修改删除和其他语句)
- [完整语法树与原文位置](#完整语法树与原文位置)
- [异常与多语句](#异常与多语句)
- [命令行使用](#命令行使用)
- [146 个纯打印示例](#146-个纯打印示例)
- [版本选项和支持边界](#版本选项和支持边界)
- [测试与项目结构](#测试与项目结构)

## 打包与安装

### 1. 准备环境

安装 JDK 17 或更高版本，确保 `JAVA_HOME` 指向该 JDK：

```bash
java -version
# macOS 已安装 JDK 17 时可以这样切换当前终端
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

项目包含 Maven Wrapper，无需另装 Maven。下列命令在项目根目录运行；首次构建需要联网下载依赖。Windows 将 `./mvnw` 换成 `mvnw.cmd`；已安装 Maven 时也可换成 `mvn`。

### 2. 打包 JAR

```bash
./mvnw clean package
```

此命令生成解析器、编译源码、运行自动化测试并打包：

| 文件 | 用途 |
|---|---|
| `target/doris-sql-parser-0.2.0.jar` | Java 库，供项目依赖；不包含第三方依赖 |
| `target/doris-sql-parser-0.2.0-cli.jar` | 可执行 CLI，已包含运行依赖，可直接 `java -jar` |

### 3. 安装到本地 Maven 仓库

**让同一台电脑上的其他 Maven 项目直接引用，执行：**

```bash
./mvnw clean install
```

`install` 包含编译、测试和打包，并把库 JAR、CLI JAR 和 POM 安装到 Maven 本地仓库。默认位置是 `~/.m2/repository`；如 `settings.xml` 设置了 `localRepository`，以该配置为准。IDEA 和命令行应使用相同的 Maven settings / 本地仓库。

只需要重新安装已修改的项目时也可以运行 `./mvnw install`。`package` 只打包，**不会安装到本地仓库**。

## 新 Maven 项目如何引用

新建 Maven 项目，Project SDK / Language level 选 **17**。先在解析器项目执行上述 `install`，再在新项目使用以下完整 POM：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>example</groupId>
    <artifactId>parser-demo</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>io.github.dorisparser</groupId>
            <artifactId>doris-sql-parser</artifactId>
            <version>0.2.0</version>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

已有项目只需添加 `dependency`，并确保编译配置使用 Java 17+。随后在 IDEA 点击 **Reload All Maven Projects**。

- 必要运行依赖 `antlr4-runtime:4.13.1` 通过 Maven 自动传递，无需手动添加。
- Jackson 是 CLI 可选依赖，普通 Java 库调用不需要它。
- **当前未发布 Maven Central。上传源码到 GitHub 不等于发布 Maven 制品。** 换一台电脑使用时，先克隆源码并运行 `install`，或由团队部署到自己的 Maven 制品库。
- 出现 `Could not find artifact` 时，检查是否已 `install`、版本是否是 `0.2.0`，以及 IDE 与终端使用的本地仓库是否相同。不要通过 `systemPath` 绑定个人电脑上的 JAR 路径。

## 第一个 Java main

保存为 `src/main/java/ParserDemo.java`，在 IDEA 运行 main：

```java
import io.github.dorisparser.DorisSqlParser;
import io.github.dorisparser.DorisVersion;

public class ParserDemo {
    public static void main(String[] args) {
        var parser = new DorisSqlParser(DorisVersion.DORIS_2_1);
        var result = parser.parseStatement("""
            SELECT u.id AS user_id, SUM(o.amount) AS total
            FROM users u LEFT JOIN orders o ON u.id = o.user_id
            WHERE o.status = 1
            GROUP BY u.id
            ORDER BY total DESC LIMIT 10
            """);
        System.out.println("语句类型：" + result.statementType());
        result.inputTables().forEach(t -> System.out.println("读表：" + t.qualifiedName()));
        System.out.println("函数：" + result.functionNames());
        for (var item : result.query().selectItems()) {
            System.out.println("表达式：" + item.expression().text());
            System.out.println("别名：" + item.alias());
            item.expression().columnReferences().forEach(c ->
                System.out.println("引用列：" + c.parts()));
        }
        System.out.println("元数据状态：" + result.metadataStatus());
        System.out.println("提示：" + result.warnings());
    }
}
```

可以看到读表 `users`、`orders`，函数 `SUM`，两项输出表达式及别名 `user_id`、`total`，引用列分别为 `[u, id]` 和 `[o, amount]`。

下文 Java 片段使用已创建的 `parser`；出现 `sql` 时，指同节展示的 SQL 字符串。完整可直接运行的综合示例在 [DorisSqlParserTestMain.java](examples/maven-consumer/src/main/java/example/DorisSqlParserTestMain.java)。

## 解析器有哪些方法

| 方法 | 返回值 / 用途 |
|---|---|
| `new DorisSqlParser()` | 默认 Doris 2.1 |
| `new DorisSqlParser(DorisVersion)` | 明确选择 2.1 或 4.0 |
| `new DorisSqlParser(DorisVersion, ParserOptions)` | 指定版本及会话语法选项 |
| `version()` | 当前解析版本 |
| `parseStatement(String)` | `SqlStatement`，恰好一条非空 SQL |
| `parseMultiStatement(String)` | `List<SqlStatement>`，解析整个脚本；空脚本返回空列表 |
| `splitSql(String)` | `List<String>`，解析后按语句返回原文，去掉外围注释和分隔符 |
| `checkSqlSyntax(String)` | 只检查单条语句语法，无返回值，失败抛异常 |
| `sqlKeywords()` | 排序去重后的关键字，包括保留字和非保留字 |
| `parseTree(String)` | 单条语句的 ANTLR 具体语法树文本 |
| `parseExpression(String)` | 表达式的语法树文本 |
| `parseSyntax(String)` | `SqlDocument`，完整脚本、结构树、全部非 EOF token |
| `parseExpressionSyntax(String)` | 表达式的 `SyntaxNode` 结构树 |

`DorisSqlHelper` 提供 `parseStatement`、`parseMultiStatement`、`splitSql`、`checkSqlSyntax`、`sqlKeywords` 静态方法。前四项可传 `(sql, version)`，关键字可传 `(version)`；省略版本默认 2.1。

结果对象是不可变 Java record，使用 `columns()`，而不是 `getColumns()`。没有元素的集合为空集合；未声明/不适用的单值通常是 `null`，使用前需判断。解析器可复用，每次调用独立创建解析状态。

## 结果从哪里读取

| `SqlStatement` 方法 | 内容 |
|---|---|
| `statementType()` / `version()` / `sql()` | 分类、版本、当前语句原文 |
| `inputTables()` / `outputTables()` | 显式读取/受修改的表；`TableId.qualifiedName()` 返回转义后的全名 |
| `functionNames()` | 函数名，归一化为大写 |
| `explained()` | 是否带 EXPLAIN |
| `limit()` / `offset()` | 语句全局可表示为 Long 的 LIMIT/OFFSET；查询层级完整值读 `query().limit()` |
| `tableDefinition()` | 建表/视图类的定义，其他语句可能为 null |
| `query()` | SELECT、INSERT 查询、CTAS 等的查询结构 |
| `insert()` | INSERT 的目标与列映射 |
| `alterActions()` | ALTER 动作分组，每项 `kind()`、`sql()` |
| `syntax()` | 全部已解析语法节点，访问尚未专门建模的语法 |
| `attributes()` | 附加摘要属性，键随语句类型而定 |
| `metadataStatus()` / `warnings()` | 当前摘要提取覆盖和提示 |

`TableId` 还提供 `parts()`、`tableName()`、`databaseName()`、`catalogName()`。限定名没有写出的部分为 null，带点的反引号标识符仍是一段名称。

**读取流程：先读常用对象，再用 `syntax()` 访问更细的语法。** `COMPLETE` 表示已实现的摘要字段覆盖完整，不代表所有语法都有专用 Java 类，也不代表数据库语义校验通过。另需查看 `query().warnings()` 和 `insert().unresolvedReasons()`。

## 建表列注释分区分桶和属性

```sql
CREATE TABLE IF NOT EXISTS user_log (
    log_id BIGINT NOT NULL COMMENT '日志ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    event_time DATETIME NOT NULL COMMENT '事件时间',
    action VARCHAR(50) DEFAULT 'view' COMMENT '行为',
    ip_addr STRING COMMENT '来源IP'
)
DUPLICATE KEY(log_id, user_id, event_time)
COMMENT '用户行为日志'
PARTITION BY RANGE(event_time) (
    PARTITION p20231001 VALUES LESS THAN ('2023-10-02'),
    PARTITION p20231002 VALUES LESS THAN ('2023-10-03')
)
DISTRIBUTED BY HASH(user_id) BUCKETS 10
PROPERTIES ('replication_num' = '1');
```

```java
var table = parser.parseStatement(sql).tableDefinition();
for (var col : table.columns()) {
    System.out.println("列=" + col.name() + ", 类型=" + col.dataType());
    System.out.println("注释=" + col.comment() + ", 可空=" + col.nullable());
    System.out.println("默认值=" + col.defaultExpression());
}
System.out.println("表注释=" + table.comment());
System.out.println("键模型=" + table.keyType());
System.out.println("键列=" + table.keyColumns());
System.out.println("CLUSTER 列=" + table.clusterKeys());
System.out.println("声明的排序列=" + table.sortColumns());
if (table.partition() != null) {
    var partition = table.partition();
    System.out.println("分区方式=" + partition.type());
    System.out.println("分区列=" + partition.columns());
    for (var part : partition.partitions()) {
        System.out.println(part.name() + " / " + part.kind());
        System.out.println("下界=" + part.lowerBound() + ", 上界=" + part.upperBound());
        System.out.println("LIST 元组=" + part.inValues());
    }
}
if (table.distribution() != null) {
    System.out.println("分桶方式=" + table.distribution().type());
    System.out.println("分桶列=" + table.distribution().columns());
    System.out.println("桶数=" + table.distribution().buckets());
}
System.out.println("副本数=" + table.properties().get("replication_num"));
table.propertyItems().forEach(p -> System.out.println(p.key() + "=" + p.value()));
```

上述 SQL 返回 5 列、对应注释、两个 LESS_THAN 分区、HASH 分桶列 `user_id`、桶数 `"10"`、副本数 `"1"`。

更多字段及读取方式：

| 对象 | 方法与含义 |
|---|---|
| 列 | `aggregateType()` 聚合类型；`key()`；`autoIncrement()`；`autoIncrementStart()`；`generatedExpression()`；`onUpdateExpression()`；`typeSyntax()` 复杂类型结构 |
| 表 | `table()`、`ifNotExists()`、`external()`、`temporary()`、`engine()`；`ctasColumns()`、`querySql()` |
| 分区 | `automatic()`、`expressions()`；分区项的 `intervalAmount()`、`intervalUnit()` 表示 STEP；`properties()` 为该分区自身属性 |
| 分区值 | `kind()`、`value()`、`sql()`，区分 STRING、INTEGER、NULL、MAXVALUE；LIST 保留多列元组 |
| 分桶 | `type()`、`columns()`、`buckets()`、`autoBuckets()`；桶数保留字符串 `"AUTO"` 或数字原文 |
| 索引 | `indexes()`；每项 `name()`、`columns()`、`type()`、`comment()`、`properties()` |
| ROLLUP | `rollups()`；每项 `name()`、`columns()`、`duplicateKeys()`、`properties()` |
| 属性 | `properties()` 方便按键取值；`propertyItems()` 保留顺序、重复键及 `keySql()` / `valueSql()` 原文；重复键在 Map 中最后值生效 |
| 外部属性 | `externalProperties()`、`externalPropertyItems()`，与普通 PROPERTIES 分开 |

`sortColumns()` 优先返回显式 CLUSTER BY 列，否则返回 KEY 列，不推断存储引擎自动补充的排序列。未声明 NULL/NOT NULL 时 `nullable()` 为 null；SQL 没写 COMMENT 时无法推断注释。STEP 分区保留定义，不枚举实际分区。

更完整的逐字段片段见 [结构化读取指南](docs/structured-access.md)。

## 查询列函数别名和关联

```sql
SELECT u.id AS user_id, SUM(o.amount) AS total
FROM users AS u LEFT JOIN orders AS o ON u.id = o.user_id
WHERE o.status = 1
GROUP BY u.id
HAVING SUM(o.amount) > 100
ORDER BY total DESC
LIMIT 10 OFFSET 5
```

```java
var s = parser.parseStatement(sql);
var q = s.query();
for (var item : q.selectItems()) {
    System.out.println("表达式=" + item.expression().text());
    System.out.println("AS=" + item.alias());
    System.out.println("是否通配符=" + item.wildcard());
    for (var col : item.expression().columnReferences()) {
        System.out.println("前缀=" + col.qualifier() + ", 列=" + col.column());
    }
}
var left = q.relations().get(0);
System.out.println("左表=" + left.table().qualifiedName() + ", 别名=" + left.alias());
for (var join : left.joins()) {
    System.out.println("连接类型=" + join.type());
    System.out.println("右侧=" + join.right().table() + ", 别名=" + join.right().alias());
    System.out.println("ON=" + (join.on() == null ? null : join.on().text()));
    System.out.println("USING=" + join.usingColumns());
}
System.out.println("WHERE=" + q.where().text());
System.out.println("GROUP=" + q.groupBy().expressions());
System.out.println("HAVING=" + q.having().text());
q.orderBy().forEach(o -> System.out.println(o.expression().text() + " " + o.direction()));
System.out.println("LIMIT=" + q.limit().count() + ", OFFSET=" + q.limit().offset());
```

条件可能不存在，业务代码需按实际语句判断 null。`RelationInfo` 也可能是子查询、表值函数或括号关联组；通用遍历先检查 `kind()`，再读取 `table()` / `subquery()` / `members()` / `function()`。

**引用列不是数据库列绑定结果。** `[u, id]` 是 SQL 中的语法名称，`u` 可能是别名、CTE 名或复杂字段路径。没有数据库 schema 时，无法可靠展开 `*`，或确定未限定列属于哪个表、SELECT 列的真实类型和数据库注释。JOIN 的 ON 保留完整表达式，不把复杂条件猜测成等值主外键。

## WITH 和多层嵌套

```sql
WITH base AS (
    SELECT user_id, amount FROM orders WHERE status = 1
), totals AS (
    SELECT user_id, SUM(amount) AS total FROM base GROUP BY user_id
)
SELECT x.uid AS final_user, x.total
FROM (
    SELECT y.user_id AS uid, y.total
    FROM (SELECT user_id, total FROM totals) AS y
) AS x
WHERE x.total > 100
```

```java
var q = parser.parseStatement(sql).query();
for (var cte : q.ctes()) {
    System.out.println("CTE=" + cte.name());
    System.out.println("CTE 列别名=" + cte.columnAliases());
    System.out.println("CTE 查询=" + cte.query().syntax().text());
}
var x = q.relations().get(0);
System.out.println(x.alias());                         // x
System.out.println(x.subquery().selectItems());        // 中间层 uid / total
var y = x.subquery().relations().get(0);
System.out.println(y.alias());                         // y
System.out.println(y.subquery().selectItems());        // 内层 user_id / total
```

每个查询层级独立返回，外层 WHERE 不混入内层。完整通用递归示例可参考综合 main 中的 `printQuery`、`printRelation`、`printExpression`、`printCtes`。

| 结构 | 访问方式 |
|---|---|
| WITH / CTE | `q.ctes()` → `CteInfo.query()` |
| FROM 子查询 | `relation.subquery()` |
| 括号关联组 | `relation.members()`，继续读取各成员的 `joins()` |
| 标量、IN、EXISTS 子查询 | 对应 `ExpressionInfo.subqueries()`，例如 `q.where().subqueries()` |
| UNION / INTERSECT / EXCEPT | `q.setOperation().operator()/quantifier()/left()/right()` |
| 整体括号查询 | `q.nestedQuery()` |
| VALUES | `q.valuesRows()`，逐行、逐列表达式 |
| GROUPING SETS / ROLLUP / CUBE | `q.groupBy().kind()/sets()/expressions()` |
| QUALIFY | `q.qualify()`，需对应版本支持 |
| 窗口函数 | `item.expression().syntax().descendants("windowSpec")`，继续读 `partitionClause`、`sortItem`、`windowFrame` |
| LATERAL VIEW | `relation.lateralViews()`，含函数、参数、表别名与列别名 |

先判断 `q.kind()`：SELECT、SET_OPERATION、VALUES、PARENTHESIZED、OTHER，避免对 UNION 顶层直接按普通 SELECT 取投影。

## INSERT INTO 和 OVERWRITE

```sql
INSERT INTO report.user_sales(user_id, total)
SELECT u.id, SUM(o.amount)
FROM users u JOIN orders o ON u.id = o.user_id
GROUP BY u.id;
```

```java
var result = parser.parseStatement(sql);
var insert = result.insert();
System.out.println(result.statementType());
System.out.println(insert.targetTableId());
System.out.println(insert.targetColumns());
for (var mapping : insert.columnMappings()) {
    System.out.println("目标列=" + mapping.targetColumn());
    System.out.println("来源序号=" + mapping.sourceOrdinal()); // 从 1 开始
    mapping.sourceExpressions().forEach(e -> System.out.println("来源表达式=" + e.text()));
}
System.out.println("未完成映射的原因=" + insert.unresolvedReasons());
System.out.println("来源查询=" + insert.query());
System.out.println("INSERT 前置 WITH=" + insert.ctes());
```

显式列示例得到 `user_id ← u.id`、`total ← SUM(o.amount)` 的位置对应。还可解析：

```sql
INSERT OVERWRITE TABLE report.user_sales(user_id, total)
SELECT user_id, SUM(amount) FROM orders GROUP BY user_id;

INSERT INTO report.user_sales(user_id, total) VALUES (1, 20), (2, 35);

WITH recent AS (SELECT user_id, amount FROM orders WHERE status = 1)
INSERT INTO report.user_sales(user_id, total)
SELECT user_id, SUM(amount) FROM recent GROUP BY user_id;
```

VALUES 逐行读取 `insert.query().valuesRows()`；UNION/VALUES 的同一目标位置可能对应多个来源表达式。INSERT 分区、LABEL、hint 等附加语法通过 `insert.syntax()` 获取。

省略目标列、投影含 `*`、来源宽度不一致时，`columnMappings()` 为空，`unresolvedReasons()` 说明原因。这里的映射是显式语法位置对应，不是已绑定物理字段的血缘。

### 复杂 INSERT SELECT 可以解析吗？

可以组合多表、JOIN、多层 AS 子查询、CASE WHEN、EXISTS、IN 和 `*`。[完整复杂 SQL](examples/complex-insert.sql) 同时包含这些结构，已在 2.1 / 4.0 两套语法运行：

- 识别 6 张来源表：users、orders、refunds、order_items、user_tags、memberships。
- 保留目标表 report.user_profile、5 个显式目标列、各层关联/条件/表达式。
- 外层 `x.*` 保留为通配符，不自动展开；列映射为空，提示 `SOURCE_WILDCARD`。当前还会按未展开的投影项数提示 `TARGET_SOURCE_ARITY_MISMATCH`，该提示不能当作展开后真实列数不一致的结论。
- 将 `x.*` 改为 `x.user_id, x.user_name, x.total_amount`，两套语法均返回 5 项显式列位置映射，未解析原因列表为空。来源仍是语法表达式，没有绑定为物理字段血缘。

复现命令：

```bash
java -jar target/doris-sql-parser-0.2.0-cli.jar --version 2.1 --file examples/complex-insert.sql
java -jar target/doris-sql-parser-0.2.0-cli.jar --version 4.0 --file examples/complex-insert.sql
```

SQL 复杂度本身不会要求切换解析方式；前提是 SQL 符合所选固定版本的语法。这一个组合例通过不代表任意 SQL 都已验证。

## 修改删除和其他语句

以下 SQL 可以分别传入 `parseStatement`，或作为脚本传入 `parseMultiStatement`：

```sql
ALTER TABLE user_log ADD COLUMN device STRING COMMENT '设备';
ALTER TABLE user_log MODIFY COLUMN action VARCHAR(100);
ALTER TABLE user_log DROP COLUMN ip_addr;
UPDATE user_log SET action = 'login' WHERE log_id = 1;
DELETE FROM user_log WHERE log_id = 1;
TRUNCATE TABLE user_log;
DROP TABLE IF EXISTS user_log;
CREATE TABLE user_summary AS SELECT user_id, COUNT(*) AS cnt FROM user_log GROUP BY user_id;
CREATE VIEW active_users AS SELECT DISTINCT user_id FROM user_log;
```

```java
var result = parser.parseStatement("ALTER TABLE user_log ADD COLUMN device STRING");
System.out.println(result.statementType());
System.out.println(result.outputTables());
for (var action : result.alterActions()) {
    System.out.println(action.kind());
    System.out.println(action.sql());
}
System.out.println(result.syntax().children());
```

ALTER 目前提供动作类型、动作原文及完整语法树，尚未给每一种修改动作建立专用 Java 类型。UPDATE、DELETE、MERGE 的目标表同时计入读表和写表；DROP/TRUNCATE 的 `outputTables()` 表示受修改的对象。SHOW/SET/USE/管理命令、LOAD/COPY/EXPORT 等摘要覆盖有限，需检查状态与提示。

EXPLAIN 的 `explained()` 为 true，保留读表依赖并清空执行写表；内层 INSERT 结构仍可描述解释计划的目标。

## 完整语法树与原文位置

常用对象没有直接提供的部分，可以沿完整语法树访问：

```java
var result = parser.parseStatement("CREATE TABLE t (id BIGINT COMMENT '编号')");
var node = result.syntax();
for (var column : node.descendants("columnDef")) {
    System.out.println(column.field("colName").text());
    System.out.println(column.field("type").text());
    var comment = column.field("comment");
    System.out.println(comment == null ? null : comment.text());
}
System.out.println(node.children());
System.out.println(node.labels());
```

| `SyntaxNode` 方法 | 用途 |
|---|---|
| `rule()` / `kind()` | g4 规则名 / 具体分支名；终结符 kind 为 token 名 |
| `text()` / `span()` | 该节点原文和源码范围 |
| `children()` | 当前节点的直接子节点，保留顺序 |
| `labels()` | 语法标签到直接子节点下标列表的映射 |
| `field(name)` / `fields(name)` | 按 g4 标签取首项 / 列表 |
| `descendants(ruleName)` | 按规则名找全部后代，包含自身 |
| `first(ruleName)` | 首个匹配节点，没有时为 null |

`field` 查标签，`first` 查规则名，两者不要混淆。规则名/标签来自所选版本 g4，两套版本可能不同；跨版本业务代码优先使用统一的结构化对象。

要连外围注释、空白、分号一起读取：

```java
var document = parser.parseSyntax("-- 开头\nSELECT '😀'; SELECT 2;");
System.out.println(document.source());
for (var token : document.tokens()) {
    System.out.println(token.kind() + ", hidden=" + token.hidden());
    System.out.println(token.span().slice(document.source()));
}
var expression = parser.parseExpressionSyntax("a + b * 2");
System.out.println(expression.children());
```

`SourceSpan` 提供 `startOffset()` / `endOffset()` / `line()` / `column()` / `endLine()` / `endColumn()`。offset 从 0 开始，左闭右开，按 Unicode 码点计数；行列从 1 开始。使用 `span.slice(完整原始输入)`，不要直接用 Java `String.substring` 截取这些 offset。多语句 span 仍相对整个输入脚本。

完整访问保证覆盖 grammar 已解析的节点和 token；字符串中的动态 SQL、注释内容、被 grammar 作为不透明 token 处理的过程体，不会进一步解析。

## 异常与多语句

```java
var statements = parser.parseMultiStatement("SELECT 1; SELECT 'a;b';");
statements.forEach(s -> System.out.println(s.sql()));
System.out.println(parser.splitSql("SELECT 1; SELECT 'a;b';"));
System.out.println(parser.sqlKeywords().size());
System.out.println(parser.parseExpression("COALESCE(price, 0) * quantity"));
try {
    parser.checkSqlSyntax("SELECT FROM");
} catch (io.github.dorisparser.SqlParseException e) {
    System.out.println("版本=" + e.version());
    System.out.println("行=" + e.line() + ", 列=" + e.column());
    System.out.println("错误 token=" + e.offendingToken());
    System.out.println(e.getMessage());
}
```

`splitSql` 先校验语法，不是用于切分任意非法文本的字符串工具；多语句遇到语法错误会抛异常。需要逐例报错后继续运行时，参考综合 main 对每个独立示例分别捕获异常的写法。

## 命令行使用

```bash
java -jar target/doris-sql-parser-0.2.0-cli.jar --sql 'SELECT * FROM sales.orders LIMIT 10'
java -jar target/doris-sql-parser-0.2.0-cli.jar --version 4.0 check --sql 'SELECT TRY_CAST(v AS INT) FROM t'
java -jar target/doris-sql-parser-0.2.0-cli.jar multi --file examples/queries.sql
java -jar target/doris-sql-parser-0.2.0-cli.jar syntax --sql 'SELECT a AS b FROM t'
echo 'SELECT 1' | java -jar target/doris-sql-parser-0.2.0-cli.jar
```

| 模式 | 输出 |
|---|---|
| `parse`（默认） | 单条 SQL 元数据 JSON |
| `multi` | 多条 SQL 元数据 JSON 数组 |
| `split` | 原文语句数组 |
| `check` | 单条语法校验结果 |
| `keywords` | 关键字数组 |
| `tree` / `expression` | 单条 SQL / 表达式语法树文本的 JSON 表示 |
| `syntax` / `expression-syntax` | 脚本及 token / 表达式结构树 JSON |

参数：`--version 2.1|4.0`、`--sql/-e`、`--file/-f`、`--ansi`、`--no-backslash-escapes`、`--help`。默认版本 2.1，未提供 SQL/文件参数时读取 UTF-8 stdin。

正常结果写 stdout，错误 JSON 写 stderr。退出码：0 成功，1 参数/文件错误，2 SQL 语法错误。PARTIAL 摘要仍属于解析成功。

## 146 个纯打印示例

[独立 Maven 示例项目](examples/maven-consumer/README.md) 可直接在 IDEA 打开。运行 [DorisSqlParserTestMain.main](examples/maven-consumer/src/main/java/example/DorisSqlParserTestMain.java)，无需连接数据库，**不使用断言**，逐条打印 SQL、解析结果、字段、关联、错误和最后汇总。

| 分类 | 数量 | 示例内容 |
|---|---:|---|
| DDL | 29 | 创建/修改/删除、列注释、分区分桶、索引、视图 |
| DML | 16 | INSERT INTO/OVERWRITE、VALUES、UPDATE、DELETE |
| QUERY | 30 | 单表、多表、函数、AS、分组、窗口、集合查询 |
| CTE | 18 | WITH、多 CTE、CTE 列别名、嵌套与多层 AS |
| ADVANCED | 23 | 更多表达式、子查询、复杂类型及关联 |
| COMMAND | 10 | SHOW、SET、USE、管理命令 |
| VERSION | 7 | 展示两套语法的接受/拒绝差异 |
| INVALID | 8 | 故意错误，观察异常位置及继续运行 |
| API | 5 | 多语句、拆分、表达式、语法树等接口 |

在项目根目录运行（macOS/Linux）：

```bash
./mvnw install
./mvnw -f examples/maven-consumer/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.DorisSqlParserTestMain
```

在 main 后面添加参数：

```text
--version 2.1              只运行 Doris 2.1；4.0 / all 同理
--category CTE             只运行某一分类
--list                     只列出示例
--tree                     额外打印完整语法树
--sql "SELECT a AS b FROM t" 解析自己的 SQL 脚本
--help                     帮助
```

默认两套版本各运行 146 个示例。VERSION 和 INVALID 有意触发语法错误，打印报错不等于示例程序故障。Windows 推荐在 IDEA 运行 main；手动拼接 Java classpath 时分隔符用 `;`。

## 版本选项和支持边界

| 模式 | 固定上游语法 | 说明 |
|---|---|---|
| `DorisVersion.DORIS_2_1` | `2.1.11-rc01` | 默认版本 |
| `DorisVersion.DORIS_4_0` | `4.0.8` | 显式选择，无自动回退 |

4.0 语法不保证完全兼容 2.1。示例中 TRY_CAST、QUALIFY、生成列等体现版本差异；同一主版本不同补丁也可能不同。当前不承诺任意 2.x/4.x SQL 全兼容。

```java
var parser40 = new io.github.dorisparser.DorisSqlParser(
    io.github.dorisparser.DorisVersion.DORIS_4_0,
    new io.github.dorisparser.ParserOptions(false, true));
// 参数依次是 noBackslashEscapes、ansiQueryOrganization
System.out.println(parser40.version());
```

默认选项都为 false。4.0 支持这两个选项，2.1 传入 true 会被明确拒绝。旧式模式下 `SELECT ... UNION ALL SELECT ... LIMIT 10` 的 LIMIT 属于右分支；4.0 ANSI 模式可以归属整个集合查询。因此应按查询层级取 LIMIT。

其他边界：

- 不检查表/列/函数是否存在，不连接数据库，不执行 SQL，不验证权限、类型、引擎约束或业务结果。
- 不展开 `*`、视图定义，不生成已经完成物理字段绑定的血缘。
- 标识符保留大小写，不根据未知的数据库配置合并名称；函数名归一化大写。
- CTE 根据可见作用域从物理读表摘要中排除，查询结构仍保留 CTE 引用。
- hint 保留原文，不验证其内部指令；不支持客户端 `DELIMITER` 命令。
- 结果保留原文和语法树，完整 JSON 可能较大。接口输出可选择需要的字段组装 DTO；只做语法校验用 `checkSqlSyntax()`。

## 测试与项目结构

```bash
./mvnw clean verify
python3 scripts/verify-sources.py
```

当前自动化回归包含 **351 项 JUnit 执行**，覆盖两套语法、官方 SQL 语料、DDL/查询/INSERT 结构、错误位置、CLI 和边界场景。来源校验覆盖 **4 份 grammar + 125 份官方 SQL**。验证范围和复现方式见 [测试说明](docs/testing.md)。综合 main 是纯打印演示，与自动化断言测试分开。

```text
doris-sql-parser/
├── pom.xml / mvnw / mvnw.cmd     构建配置与 Maven Wrapper
├── src/main/antlr4/             两套固定版本的官方语法
├── src/main/java/               Java API、结构提取、模型和 CLI
├── src/test/                    自动化测试及官方 SQL 语料
├── examples/maven-consumer/     独立 Maven 项目、146 个打印示例
├── examples/queries.sql         CLI 多语句输入示例
├── docs/                        结构化读取指南、测试说明
├── scripts/verify-sources.py     上游来源哈希校验
└── grammar-sources.json / LICENSE / NOTICE
```

语法文件保持上游原始字节，来源 tag、commit、路径、SHA-256 记录在 `grammar-sources.json`。两套语法统一由 ANTLR 4.13.1 生成；Doris 2.1 原工程使用 4.9.3，本项目通过回归验证使用范围，不声称生成器完全等价。

API 设计参考 [superior-sql-parser](https://github.com/melin/superior-sql-parser)，独立解析封装参考 [Apache Doris](https://github.com/apache/doris) 的 `fe-sql-parser`。遵循 [Apache License 2.0](LICENSE)，来源声明见 [NOTICE](NOTICE)。本项目是独立工具，并非 Apache 官方发布件。
