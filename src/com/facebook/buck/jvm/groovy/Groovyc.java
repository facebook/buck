package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class Groovyc implements Tool {

  public static String BIN_GROOVYC = "bin/groovyc";

  @AddToRuleKey private final Supplier<? extends SourcePath> path;
  @AddToRuleKey private final boolean external;

  public Groovyc(Supplier<? extends SourcePath> path, boolean external) {
    this.path = path;
    this.external = external;
  }

  public Groovyc(@Nullable SourcePath path, boolean external) {
    this(() -> Objects.requireNonNull(path), external);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    Path toolPath = resolver.getAbsolutePath(path.get());

    if (!external) {
      toolPath = toolPath.resolve(BIN_GROOVYC);
    }
    return ImmutableList.of(toolPath.toString());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return ImmutableMap.of();
  }
}
