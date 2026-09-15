package io.github.dorisparser.internal;

import io.github.dorisparser.model.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/** Internal CST adapter: keeps version-specific generated classes out of metadata code. */
public final class SqlNode {
  private final ParserRuleContext context;
  private final String[] rules;
  private final String sourceOverride;

  public SqlNode(ParserRuleContext context, String[] rules) {
    this(context, rules, null);
  }

  public SqlNode(ParserRuleContext context, String[] rules, String sourceOverride) {
    this.context = context;
    this.rules = rules;
    this.sourceOverride = sourceOverride;
  }

  public String kind() {
    return context.getClass().getSimpleName().replaceFirst("Context$", "");
  }

  public String rule() {
    return rules[context.getRuleIndex()];
  }

  public String text() {
    if (sourceOverride != null) return sourceOverride;
    if (context.getStart() == null
        || context.getStop() == null
        || context.getStop().getStopIndex() < context.getStart().getStartIndex()) return "";
    return context
        .getStart()
        .getInputStream()
        .getText(Interval.of(context.getStart().getStartIndex(), context.getStop().getStopIndex()));
  }

  public List<SqlNode> children() {
    List<SqlNode> out = new ArrayList<>();
    for (int i = 0; i < context.getChildCount(); i++)
      if (context.getChild(i) instanceof ParserRuleContext c) out.add(new SqlNode(c, rules));
    return List.copyOf(out);
  }

  public List<SqlNode> children(String rule) {
    return children().stream().filter(n -> n.rule().equals(rule)).toList();
  }

  public SqlNode child(String rule) {
    return children(rule).stream().findFirst().orElse(null);
  }

  public SqlNode field(String name) {
    Object v = value(name);
    return v instanceof ParserRuleContext c ? new SqlNode(c, rules) : null;
  }

  public String fieldText(String name) {
    Object v = value(name);
    return v instanceof ParserRuleContext c
        ? new SqlNode(c, rules).text()
        : v instanceof Token t ? t.getText() : null;
  }

  private Object value(String name) {
    try {
      return context.getClass().getField(name).get(context);
    } catch (NoSuchFieldException e) {
      return null;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<String> tokens() {
    List<String> out = new ArrayList<>();
    collectTokens(context, out);
    return List.copyOf(out);
  }

  private static void collectTokens(ParseTree t, List<String> out) {
    if (t instanceof org.antlr.v4.runtime.tree.TerminalNode n) {
      if (n.getSymbol().getType() != Token.EOF) out.add(n.getText());
    } else for (int i = 0; i < t.getChildCount(); i++) collectTokens(t.getChild(i), out);
  }

  /** Immediate terminal children only; identifiers in child rules are not SQL clause keywords. */
  public List<String> directTokens() {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < context.getChildCount(); i++) {
      if (context.getChild(i) instanceof org.antlr.v4.runtime.tree.TerminalNode n
          && n.getSymbol().getType() != Token.EOF) out.add(n.getText());
    }
    return List.copyOf(out);
  }

  /** Leaf-token index for an immediate keyword; nested expression tokens cannot shadow it. */
  public int directTokenIndex(String keyword) {
    List<Token> leaves = new ArrayList<>();
    collectLeafTokens(context, leaves);
    for (int i = 0; i < context.getChildCount(); i++) {
      if (context.getChild(i) instanceof TerminalNode n && n.getText().equalsIgnoreCase(keyword))
        return leaves.indexOf(n.getSymbol());
    }
    return -1;
  }

  /** Original source between leaf tokens; indexes use the same ordering as tokens(). */
  public String tokenSlice(int startInclusive, int endExclusive) {
    List<Token> leaves = new ArrayList<>();
    collectLeafTokens(context, leaves);
    if (startInclusive == endExclusive) return "";
    Token start = leaves.get(startInclusive), end = leaves.get(endExclusive - 1);
    return start.getInputStream().getText(Interval.of(start.getStartIndex(), end.getStopIndex()));
  }

  private static void collectLeafTokens(ParseTree tree, List<Token> out) {
    if (tree instanceof org.antlr.v4.runtime.tree.TerminalNode n) {
      if (n.getSymbol().getType() != Token.EOF) out.add(n.getSymbol());
    } else for (int i = 0; i < tree.getChildCount(); i++) collectLeafTokens(tree.getChild(i), out);
  }

  public SourceSpan span() {
    Token start = context.getStart();
    return span(start, text());
  }

  public static SourceSpan span(Token start, String text) {
    int offset = Math.max(0, start.getStartIndex());
    int line = start.getLine(), column = start.getCharPositionInLine() + 1;
    int endLine = line, endColumn = column;
    for (int cp : text.codePoints().toArray()) {
      if (cp == '\n') {
        endLine++;
        endColumn = 1;
      } else endColumn++;
    }
    return new SourceSpan(
        offset, offset + text.codePointCount(0, text.length()), line, column, endLine, endColumn);
  }

  /** Export all grammar children and token labels; no generated classes escape the public API. */
  public SyntaxNode syntax() {
    List<SyntaxNode> children = new ArrayList<>();
    IdentityHashMap<Object, Integer> indexes = new IdentityHashMap<>();
    for (int i = 0; i < context.getChildCount(); i++) {
      ParseTree child = context.getChild(i);
      if (child instanceof ParserRuleContext c) {
        indexes.put(c, children.size());
        children.add(new SqlNode(c, rules).syntax());
      } else if (child instanceof TerminalNode terminal
          && terminal.getSymbol().getType() != Token.EOF) {
        Token t = terminal.getSymbol();
        indexes.put(t, children.size());
        String name =
            t.getTokenSource() instanceof Lexer lexer
                ? lexer.getVocabulary().getSymbolicName(t.getType())
                : null;
        children.add(
            new SyntaxNode(
                "",
                name == null ? "TOKEN_" + t.getType() : name,
                t.getText(),
                span(t, t.getText()),
                List.of(),
                Map.of()));
      }
    }
    Map<String, List<Integer>> labels = new TreeMap<>();
    for (java.lang.reflect.Field f : context.getClass().getFields()) {
      if (f.getDeclaringClass().getPackageName().startsWith("org.antlr.")) continue;
      try {
        Object value = f.get(context);
        List<?> values =
            value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
        List<Integer> matches = values.stream().map(indexes::get).filter(Objects::nonNull).toList();
        if (!matches.isEmpty()) labels.put(f.getName(), matches);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    return new SyntaxNode(rule(), kind(), text(), span(), children, labels);
  }

  public String tree() {
    return context.toStringTree(Arrays.asList(rules));
  }
}
