package io.github.dorisparser;

public record ParserOptions(boolean noBackslashEscapes, boolean ansiQueryOrganization) {
  public static final ParserOptions DEFAULT = new ParserOptions(false, false);
}
