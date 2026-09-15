# 测试与复现

环境：JDK 17+。以下命令在项目根目录执行；Windows 使用 `mvnw.cmd`。

## 自动化回归

```bash
./mvnw clean verify
python3 scripts/verify-sources.py
```

当前 351 项 JUnit 执行：

| 测试类 | 执行数 | 关注点 |
|---|---:|---|
| UpstreamCorpusTest | 250 | 125 份官方 SQL × 两套语法 |
| QueryStructureTest | 33 | 查询层级、CTE、表达式、INSERT 映射 |
| MetadataTest | 26 | 分类、读写表、DDL 摘要 |
| DdlStructureTest | 11 | 列、注释、分区、分桶、属性 |
| ReviewRegressionTest | 8 | 作用域等边界回归 |
| ParserEdgeTest | 7 | 语法边界 |
| CliTest | 7 | CLI 模式与错误 |
| SyntaxStructureTest | 6 | 完整结构树、源码位置 |
| ParserTest | 3 | 门面基础行为 |

官方语料有 121 份有效查询、3 份全注释脚本、1 份上游原始多右括号 SQL；测试分别验证成功、空输入行为和预期拒绝。原文件未修改。官方语料的摘要断言主要是分类/非空读表检查，不能当作每个字段的正确性证明。来源校验检查 4 个 g4 和 125 个 SQL，共 129 个 SHA-256。

生成器可能报告上游 grammar 的 implicit token / nullable rule 警告；四份语法保持原始字节，不为消除提示修改上游文件。Maven shade 也可能报告依赖中的重叠清单/许可证资源。以构建退出码及测试报告判断结果。

## 独立消费者与打印示例

```bash
./mvnw clean install
./mvnw -f examples/maven-consumer/pom.xml clean compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "examples/maven-consumer/target/classes:$(cat examples/maven-consumer/target/classpath.txt)" example.DorisSqlParserTestMain > examples/maven-consumer/target/sql-examples-output.txt
```

main 含 146 个示例，默认双版本共 292 次示例调用。当前预期输出汇总为 269 次解析完成、23 次语法报错、0 次其他异常；语法报错包括 16 次故意非法输入和 7 次版本差异。它不使用断言，打印次数不是自动化测试通过数。查看输出中的 SQL、结构和错误位置，或使用 `--category` / `--version` 缩小范围。

以上只验证解析器和结构提取，不连接 Doris，不执行 SQL，也不验证 SQL 是否满足真实数据库表结构、类型和业务约束。
