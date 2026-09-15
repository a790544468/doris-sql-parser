package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;

/** Explicit ordering; omitted direction/null ordering remain null. */
public record OrderItem(
    ExpressionInfo expression, String direction, String nullOrdering, SyntaxNode syntax) {}
