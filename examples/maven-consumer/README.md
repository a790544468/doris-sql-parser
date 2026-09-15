# 独立 Maven / Java 17 示例

这是实际引用 `io.github.dorisparser:doris-sql-parser:0.2.0` 的独立项目。包含 [DorisSqlParserTestMain.java](src/main/java/example/DorisSqlParserTestMain.java)：146 个示例，逐条打印，不使用断言，不连接数据库。

## IDEA 运行

1. 在解析器根目录执行 `./mvnw clean install`，先安装本地 JAR 和 POM。
2. 在 IDEA 打开本目录的 `pom.xml`，作为 Maven 项目加载。
3. Project SDK、Language level、Maven Runner JRE 选择 JDK 17+。
4. 保证 IDEA 的 Maven settings / 本地仓库与第 1 步相同，刷新 Maven。
5. 打开 `DorisSqlParserTestMain`，运行 `main`。

## 命令行运行

在解析器根目录执行（macOS/Linux）：

```bash
./mvnw install
./mvnw -f examples/maven-consumer/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.DorisSqlParserTestMain
```

Windows 使用 `mvnw.cmd`，Java classpath 分隔符为 `;`；也可以直接用 IDEA 运行。安装了 Maven 可用 `mvn` 替代 wrapper。

## 参数

在上述 main 类名后面追加：

| 参数 | 用途 |
|---|---|
| `--version 2.1` / `4.0` / `all` | 选择语法版本，默认 all |
| `--category DDL` | 分类过滤，可选 DDL、DML、QUERY、CTE、ADVANCED、COMMAND、VERSION、INVALID、API |
| `--list` | 只列出示例名称 |
| `--tree` | 额外打印完整语法树 |
| `--sql "SELECT a AS b FROM t"` | 临时解析自己的 SQL 脚本 |
| `--help` | 显示帮助 |

例如只看 2.1 的 CTE：

```bash
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.DorisSqlParserTestMain --version 2.1 --category CTE
```

默认 146 个示例 × 两个版本。VERSION 演示接受/拒绝差异，INVALID 故意包含错误 SQL；每例单独捕获异常，报错后继续下一例。末尾按版本/分类汇总解析完成、语法报错、其他异常次数。

输出内容包括：语句类型、读写表、函数、列及注释、分区分桶、属性、SELECT 表达式与列引用、JOIN/条件、CTE/子查询、INSERT 目标列及位置映射、未解析原因、语法片段。

没有指定目标列或来源包含 `*` 时，INSERT 映射会说明无法确定。解析器不读取数据库 schema，也不会猜测 `*` 展开结果。

完整安装、POM、字段访问说明见 [项目 README](../../README.md)。
