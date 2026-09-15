package io.github.dorisparser.model;

/** Zero-based Unicode code-point offsets, end exclusive; one-based lines and columns. */
public record SourceSpan(
    int startOffset, int endOffset, int line, int column, int endLine, int endColumn) {
  /** Slice the original complete input, including when this span belongs to a later statement. */
  public String slice(String source) {
    return source.substring(
        source.offsetByCodePoints(0, startOffset), source.offsetByCodePoints(0, endOffset));
  }
}
