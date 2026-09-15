package io.github.dorisparser;

import io.github.dorisparser.internal.CaseInsensitiveStream;
import io.github.dorisparser.internal.MetadataExtractor;
import io.github.dorisparser.internal.SqlNode;
import io.github.dorisparser.model.*;
import java.util.*;
import org.antlr.v4.runtime.*;

/** Thread-safe facade. Each invocation creates its own lexer, parser, and metadata state. */
public final class DorisSqlParser {
  private final DorisVersion version;
  private final ParserOptions options;

  public DorisSqlParser() {
    this(DorisVersion.DORIS_2_1);
  }

  public DorisSqlParser(DorisVersion version) {
    this(version, ParserOptions.DEFAULT);
  }

  public DorisSqlParser(DorisVersion version, ParserOptions options) {
    this.version = Objects.requireNonNull(version, "version");
    this.options = Objects.requireNonNull(options, "options");
    if (version == DorisVersion.DORIS_2_1 && options.ansiQueryOrganization())
      throw new IllegalArgumentException(
          "ANSI query organization is not supported by the pinned 2.1 grammar");
    if (version == DorisVersion.DORIS_2_1 && options.noBackslashEscapes())
      throw new IllegalArgumentException(
          "NO_BACKSLASH_ESCAPES is not exposed by the pinned 2.1 lexer");
  }

  public DorisVersion version() {
    return version;
  }

  public SqlStatement parseStatement(String sql) {
    return MetadataExtractor.extract(
        parse(sql, Mode.SINGLE).statements().get(0), version, options.noBackslashEscapes());
  }

  public List<SqlStatement> parseMultiStatement(String sql) {
    return parse(sql, Mode.MULTI).statements().stream()
        .map(n -> MetadataExtractor.extract(n, version, options.noBackslashEscapes()))
        .toList();
  }

  /**
   * Parses a valid script and returns statement source slices, without separators/surrounding
   * comments.
   */
  public List<String> splitSql(String sql) {
    return parse(sql, Mode.MULTI).statements().stream().map(SqlNode::text).toList();
  }

  /**
   * Throws SqlParseException on invalid/empty/multiple statements. Performs no metadata analysis.
   */
  public void checkSqlSyntax(String sql) {
    parse(sql, Mode.SINGLE);
  }

  public String parseExpression(String sql) {
    return parse(sql, Mode.EXPRESSION).root().tree();
  }

  public String parseTree(String sql) {
    return parse(sql, Mode.SINGLE).root().tree();
  }

  /**
   * Parse a complete script, including empty scripts, keeping comments, whitespace and separators.
   */
  public SqlDocument parseSyntax(String sql) {
    Parsed parsed = parse(sql, Mode.MULTI);
    return new SqlDocument(sql, parsed.root().syntax(), parsed.tokens());
  }

  public SyntaxNode parseExpressionSyntax(String sql) {
    return parse(sql, Mode.EXPRESSION).root().syntax();
  }

  public List<String> sqlKeywords() {
    var vocabulary = lexer("").getVocabulary();
    SortedSet<String> words = new TreeSet<>();
    for (int i = 1; i <= vocabulary.getMaxTokenType(); i++) {
      String literal = vocabulary.getLiteralName(i);
      if (literal != null && literal.matches("'[A-Z][A-Z_0-9]*'"))
        words.add(literal.substring(1, literal.length() - 1));
    }
    return List.copyOf(words);
  }

  private enum Mode {
    SINGLE,
    MULTI,
    EXPRESSION
  }

  private record Parsed(SqlNode root, List<SqlNode> statements, List<SyntaxToken> tokens) {}

  private Parsed parse(String sql, Mode mode) {
    Objects.requireNonNull(sql, "sql");
    Lexer lexer = lexer(sql);
    BaseErrorListener listener =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object symbol,
              int line,
              int column,
              String message,
              RecognitionException cause) {
            throw error(sql, line, column, symbol instanceof Token t ? t.getText() : null, message);
          }
        };
    lexer.removeErrorListeners();
    lexer.addErrorListener(listener);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    tokens.fill();
    for (Token t : tokens.getTokens())
      if ("UNRECOGNIZED".equals(lexer.getVocabulary().getSymbolicName(t.getType())))
        throw error(
            sql,
            t.getLine(),
            t.getCharPositionInLine(),
            t.getText(),
            "Unrecognized character or unterminated quote");
    for (Token t : tokens.getTokens())
      if ("BRACKETED_COMMENT".equals(lexer.getVocabulary().getSymbolicName(t.getType()))
          && !balancedComment(t.getText()))
        throw error(
            sql,
            t.getLine(),
            t.getCharPositionInLine(),
            t.getText(),
            "Unterminated nested block comment");
    if (lexer instanceof io.github.dorisparser.antlr.v21.DorisLexer old
        && old.has_unclosed_bracketed_comment) {
      Token t =
          tokens.getTokens().stream()
              .filter(
                  x ->
                      "BRACKETED_COMMENT"
                          .equals(lexer.getVocabulary().getSymbolicName(x.getType())))
              .reduce((a, b) -> b)
              .orElse(tokens.get(tokens.size() - 1));
      throw error(
          sql, t.getLine(), t.getCharPositionInLine(), t.getText(), "Unterminated block comment");
    }
    Parser parser;
    if (version == DorisVersion.DORIS_2_1) {
      var p = new io.github.dorisparser.antlr.v21.DorisParser(tokens);
      p.doris_legacy_SQL_syntax = !options.ansiQueryOrganization();
      parser = p;
    } else {
      var p = new io.github.dorisparser.antlr.v40.DorisParser(tokens);
      p.ansiSQLSyntax = options.ansiQueryOrganization();
      parser = p;
    }
    parser.removeErrorListeners();
    parser.addErrorListener(listener);
    ParserRuleContext context;
    if (parser instanceof io.github.dorisparser.antlr.v21.DorisParser p) {
      context =
          switch (mode) {
            case SINGLE -> p.singleStatement();
            case MULTI -> p.multiStatements();
            case EXPRESSION -> p.expression();
          };
    } else {
      var p = (io.github.dorisparser.antlr.v40.DorisParser) parser;
      context =
          switch (mode) {
            case SINGLE -> p.singleStatement();
            case MULTI -> p.multiStatements();
            case EXPRESSION -> p.expressionWithEof();
          };
    }
    if (parser.getCurrentToken().getType() != Token.EOF) {
      Token t = parser.getCurrentToken();
      throw error(
          sql, t.getLine(), t.getCharPositionInLine(), t.getText(), "Unexpected trailing input");
    }
    validateIdentifierSyntax(context, sql);
    SqlNode root = new SqlNode(context, parser.getRuleNames());
    List<SqlNode> statements =
        statementNodes(context, parser.getRuleNames(), tokens, lexer.getVocabulary());
    if (mode == Mode.SINGLE && statements.size() != 1)
      throw error(sql, 1, 0, null, "Expected exactly one non-empty statement");
    return new Parsed(
        root,
        statements,
        tokens.getTokens().stream()
            .filter(t -> t.getType() != Token.EOF)
            .map(
                t ->
                    new SyntaxToken(
                        lexer.getVocabulary().getSymbolicName(t.getType()),
                        t.getText(),
                        t.getChannel(),
                        SqlNode.span(t, t.getText())))
            .toList());
  }

  private static List<SqlNode> statementNodes(
      ParserRuleContext root, String[] rules, CommonTokenStream tokens, Vocabulary vocabulary) {
    List<SqlNode> result = new ArrayList<>();
    for (int i = 0; i < root.getChildCount(); i++) {
      if (!(root.getChild(i) instanceof ParserRuleContext c)
          || !rules[c.getRuleIndex()].equals("statement")) continue;
      int end = c.getStop().getStopIndex();
      // The upstream 2.1 lexer intentionally hides FROM DUAL. It still belongs to the source SQL.
      for (int j = c.getStop().getTokenIndex() + 1; j < tokens.size(); j++) {
        Token t = tokens.get(j);
        if (t.getChannel() == Token.DEFAULT_CHANNEL) break;
        if ("FROM_DUAL".equals(vocabulary.getSymbolicName(t.getType()))) end = t.getStopIndex();
      }
      String source =
          c.getStart()
              .getInputStream()
              .getText(org.antlr.v4.runtime.misc.Interval.of(c.getStart().getStartIndex(), end));
      result.add(new SqlNode(c, rules, source));
    }
    return List.copyOf(result);
  }

  private void validateIdentifierSyntax(ParserRuleContext context, String sql) {
    Deque<ParserRuleContext> pending = new ArrayDeque<>();
    pending.push(context);
    while (!pending.isEmpty()) {
      ParserRuleContext node = pending.pop();
      // Equivalent to Doris PostProcessor.exitErrorIdent, without rewriting CST tokens.
      if (node.getClass().getSimpleName().equals("ErrorIdentContext")) {
        Token t = node.getStart();
        throw error(
            sql,
            t.getLine(),
            t.getCharPositionInLine(),
            t.getText(),
            "Unquoted identifier contains a hyphen; enclose the identifier in backticks");
      }
      for (int i = 0; i < node.getChildCount(); i++)
        if (node.getChild(i) instanceof ParserRuleContext c) pending.push(c);
    }
  }

  private static boolean balancedComment(String comment) {
    int depth = 0;
    for (int i = 0; i + 1 < comment.length(); i++) {
      if (comment.charAt(i) == '/' && comment.charAt(i + 1) == '*') {
        depth++;
        i++;
      } else if (comment.charAt(i) == '*' && comment.charAt(i + 1) == '/') {
        depth--;
        i++;
      }
    }
    return depth == 0;
  }

  private Lexer lexer(String sql) {
    var stream = new CaseInsensitiveStream(CharStreams.fromString(sql));
    if (version == DorisVersion.DORIS_2_1)
      return new io.github.dorisparser.antlr.v21.DorisLexer(stream);
    var lexer = new io.github.dorisparser.antlr.v40.DorisLexer(stream);
    lexer.isNoBackslashEscapes = options.noBackslashEscapes();
    return lexer;
  }

  private SqlParseException error(
      String sql, int line, int zeroBasedColumn, String token, String reason) {
    return new SqlParseException(version, sql, line, zeroBasedColumn + 1, token, reason);
  }
}
