package io.github.dorisparser.internal;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.misc.Interval;

/** Uppercases lookahead only; token text and source intervals retain the original input. */
public final class CaseInsensitiveStream implements CharStream {
  private final CharStream delegate;

  public CaseInsensitiveStream(CharStream delegate) {
    this.delegate = delegate;
  }

  @Override
  public String getText(Interval interval) {
    return delegate.getText(interval);
  }

  @Override
  public void consume() {
    delegate.consume();
  }

  @Override
  public int LA(int i) {
    int c = delegate.LA(i);
    return c <= 0 ? c : Character.toUpperCase(c);
  }

  @Override
  public int mark() {
    return delegate.mark();
  }

  @Override
  public void release(int marker) {
    delegate.release(marker);
  }

  @Override
  public int index() {
    return delegate.index();
  }

  @Override
  public void seek(int index) {
    delegate.seek(index);
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public String getSourceName() {
    return delegate.getSourceName();
  }
}
