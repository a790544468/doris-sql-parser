package io.github.dorisparser.model.ddl;

import java.util.*;

public record DistributionDefinition(String type, List<String> columns, String buckets) {
  public DistributionDefinition {
    columns = List.copyOf(columns);
  }

  public boolean autoBuckets() {
    return "AUTO".equalsIgnoreCase(buckets);
  }
}
