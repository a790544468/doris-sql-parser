package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;

/** Binary set-operation tree preserving grammar precedence and each branch scope. */
public record SetOperationInfo(
    String operator, String quantifier, QueryInfo left, QueryInfo right, SyntaxNode syntax) {}
