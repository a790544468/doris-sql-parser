package io.github.dorisparser.internal.metadata;

import io.github.dorisparser.internal.SqlNode;
import io.github.dorisparser.model.TableId;
import java.util.*;

/** Small CST utilities; identifier boundaries always come from grammar nodes. */
public final class Nodes {
  private Nodes() {}

  public static String unquote(String text) {
    if (text == null || text.length() < 2) return text;
    char quote = text.charAt(0);
    if ((quote == '`' || quote == '\'' || quote == '"')
        && text.charAt(text.length() - 1) == quote) {
      String body = text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
      if (quote != '`') body = body.replace("\\" + quote, "" + quote).replace("\\\\", "\\");
      return body;
    }
    return text;
  }

  /** Decode SQL string tokens according to the lexer mode; identifier dots stay untouched. */
  public static String stringLiteral(String text, boolean noBackslashEscapes) {
    if (text == null || text.length() < 2 || (text.charAt(0) != '\'' && text.charAt(0) != '"'))
      return unquote(text);
    char quote = text.charAt(0);
    if (text.charAt(text.length() - 1) != quote) return text;
    String body = text.substring(1, text.length() - 1);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == quote && i + 1 < body.length() && body.charAt(i + 1) == quote) {
        result.append(quote);
        i++;
      } else if (c == '\\' && !noBackslashEscapes && i + 1 < body.length()) {
        char next = body.charAt(++i);
        switch (next) {
          case '0' -> result.append('\0');
          case 'b' -> result.append('\b');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'Z' -> result.append((char) 26);
          case '%', '_' -> result.append('\\').append(next);
          default -> result.append(next);
        }
      } else result.append(c);
    }
    return result.toString();
  }

  public static TableId table(SqlNode node) {
    if (node == null) return null;
    List<SqlNode> parts = node.children("errorCapturingIdentifier");
    if (parts.isEmpty()) parts = node.children("identifier");
    return new TableId(
        parts.isEmpty()
            ? List.of(unquote(node.text()))
            : parts.stream().map(p -> unquote(String.join("", p.tokens()))).toList());
  }

  public static List<String> identifiers(SqlNode node) {
    if (node == null) return List.of();
    SqlNode seq = node.child("identifierSeq");
    if (seq != null)
      return seq.children().stream().map(n -> unquote(String.join("", n.tokens()))).toList();
    return node.children("identifier").stream()
        .map(n -> unquote(String.join("", n.tokens())))
        .toList();
  }

  public static SqlNode field(SqlNode node, String... names) {
    for (String name : names) {
      SqlNode field = node.field(name);
      if (field != null) return field;
    }
    return null;
  }

  public static String upper(String text) {
    return text == null ? null : text.toUpperCase(Locale.ROOT);
  }

  public static boolean has(List<String> tokens, String token) {
    return tokens.stream().anyMatch(t -> t.equalsIgnoreCase(token));
  }

  public static int index(List<String> tokens, String token) {
    for (int i = 0; i < tokens.size(); i++) if (tokens.get(i).equalsIgnoreCase(token)) return i;
    return -1;
  }

  public static String after(List<String> tokens, String token) {
    int i = index(tokens, token);
    return i >= 0 && i + 1 < tokens.size() ? tokens.get(i + 1) : null;
  }

  /** Direct clause terminals outside parentheses; child identifiers cannot shadow keywords. */
  public static List<String> topTokens(SqlNode node) {
    List<String> all = node.directTokens();
    List<String> out = new ArrayList<>();
    int depth = 0;
    for (String t : all) {
      if (t.equals("(")) depth++;
      else if (t.equals(")")) depth--;
      else if (depth == 0) out.add(t);
    }
    return out;
  }

  public static List<SqlNode> descendants(SqlNode node, String rule) {
    List<SqlNode> result = new ArrayList<>();
    if (node.rule().equals(rule)) result.add(node);
    for (SqlNode child : node.children()) result.addAll(descendants(child, rule));
    return result;
  }
}
