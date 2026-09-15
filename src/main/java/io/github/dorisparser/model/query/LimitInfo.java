package io.github.dorisparser.model.query;

import io.github.dorisparser.model.SyntaxNode;

/** Exact unsigned integer token text; no overflow or implicit offset is introduced. */
public record LimitInfo(String count, String offset, SyntaxNode syntax) {}
