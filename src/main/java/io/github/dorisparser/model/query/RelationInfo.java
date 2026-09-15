package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;
import io.github.dorisparser.model.TableId;
import java.util.List;

/**
 * One FROM relation. TABLE names can denote CTEs; table does not assert a physical binding. Joins
 * and nested groups retain their local ownership.
 */
public record RelationInfo(
    Kind kind,
    TableId table,
    String function,
    String alias,
    List<String> columnAliases,
    QueryInfo subquery,
    List<RelationInfo> members,
    List<JoinInfo> joins,
    List<SyntaxNode> hints,
    List<LateralViewInfo> lateralViews,
    SyntaxNode syntax) {
  public RelationInfo {
    columnAliases = List.copyOf(columnAliases);
    members = List.copyOf(members);
    joins = List.copyOf(joins);
    hints = List.copyOf(hints);
    lateralViews = List.copyOf(lateralViews);
  }

  public enum Kind {
    TABLE,
    SUBQUERY,
    TABLE_FUNCTION,
    GROUP,
    OTHER
  }
}
