package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;

/**
 * One output expression. wildcard is true only for a projection star, not COUNT(*) or
 * multiplication.
 */
public record SelectItem(
    ExpressionInfo expression, String alias, boolean wildcard, SyntaxNode syntax) {}
