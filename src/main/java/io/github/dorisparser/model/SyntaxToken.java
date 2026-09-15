package io.github.dorisparser.model;

/** A lexer token, including whitespace and comments; EOF is omitted. */
public record SyntaxToken(String kind, String text, int channel, SourceSpan span) {
  public boolean hidden() {
    return channel != 0;
  }
}
