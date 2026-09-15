package io.github.dorisparser.model;

import java.util.List;

/** Complete original script and parsed grammar structure, with all tokens in source order. */
public record SqlDocument(String source, SyntaxNode root, List<SyntaxToken> tokens) {
  public SqlDocument {
    tokens = List.copyOf(tokens);
  }
}
