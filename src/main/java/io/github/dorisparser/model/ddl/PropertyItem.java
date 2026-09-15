package io.github.dorisparser.model.ddl;

import io.github.dorisparser.model.SyntaxNode;
import java.util.*;

public record PropertyItem(
    String key, String value, String keySql, String valueSql, SyntaxNode syntax) {}
