package io.github.dorisparser.model;

import java.util.*;

/**
 * Immutable grammar tree. Terminal nodes have an empty rule and a lexer token name as kind. Labels
 * point to direct child indexes to avoid cycles and duplicated subtrees in JSON. Rule/kind/label
 * names intentionally follow each pinned version's grammar.
 */
public record SyntaxNode(
    String rule,
    String kind,
    String text,
    SourceSpan span,
    List<SyntaxNode> children,
    Map<String, List<Integer>> labels) {
  public SyntaxNode {
    children = List.copyOf(children);
    Map<String, List<Integer>> copy = new LinkedHashMap<>();
    labels.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    labels = Collections.unmodifiableMap(copy);
  }

  public SyntaxNode field(String label) {
    var indexes = labels.getOrDefault(label, List.of());
    return indexes.isEmpty() ? null : children.get(indexes.get(0));
  }

  public List<SyntaxNode> fields(String label) {
    return labels.getOrDefault(label, List.of()).stream().map(children::get).toList();
  }

  /** Preorder including this node when its rule matches. */
  public List<SyntaxNode> descendants(String ruleName) {
    List<SyntaxNode> result = new ArrayList<>();
    Deque<SyntaxNode> pending = new ArrayDeque<>();
    pending.push(this);
    while (!pending.isEmpty()) {
      SyntaxNode node = pending.pop();
      if (node.rule.equals(ruleName)) result.add(node);
      for (int i = node.children.size() - 1; i >= 0; i--) pending.push(node.children.get(i));
    }
    return List.copyOf(result);
  }

  public SyntaxNode first(String ruleName) {
    if (rule.equals(ruleName)) return this;
    for (SyntaxNode child : children) {
      SyntaxNode found = child.first(ruleName);
      if (found != null) return found;
    }
    return null;
  }
}
