package com.facebook.buck.rules.args;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableCollection;

/**
 * An {@link Arg} implementation that delegates to another arg. Could be useful for overriding
 * one of the functions of the underlying Arg.
 */
public class ProxyArg implements Arg {
  protected final Arg arg;

  public ProxyArg(Arg arg) {
    this.arg = arg;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    arg.appendToRuleKey(sink);
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return arg.getDeps(ruleFinder);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return arg.getInputs();
  }

  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    arg.appendToCommandLine(builder, pathResolver);
  }

  @Override
  public String toString() {
    return arg.toString();
  }

  @Override
  public boolean equals(Object other) {
    return arg.equals(other) && getClass().equals(other.getClass());
  }

  @Override
  public int hashCode() {
    return arg.hashCode();
  }
}
