package io.github.dorisparser;

/** Exact pinned grammar baselines, not a promise covering every 2.x/4.x release. */
public enum DorisVersion {
  DORIS_2_1("2.1", "2.1.11-rc01"),
  DORIS_4_0("4.0", "4.0.8");
  private final String id;
  private final String sourceTag;

  DorisVersion(String id, String sourceTag) {
    this.id = id;
    this.sourceTag = sourceTag;
  }

  public String id() {
    return id;
  }

  public String sourceTag() {
    return sourceTag;
  }

  public static DorisVersion fromString(String value) {
    for (DorisVersion v : values()) if (v.id.equals(value) || v.sourceTag.equals(value)) return v;
    throw new IllegalArgumentException("Unsupported version: " + value + "; choose 2.1 or 4.0");
  }
}
