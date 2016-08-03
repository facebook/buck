package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.regex.Pattern;

public class PrebuiltAppleFrameworkDescription implements
    Description<PrebuiltAppleFrameworkDescription.Arg>,
    MetadataProvidingDescription<PrebuiltAppleFrameworkDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("prebuilt_apple_framework");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public PrebuiltAppleFrameworkDescription.Arg createUnpopulatedConstructorArg() {
    return new PrebuiltAppleFrameworkDescription.Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) throws NoSuchBuildTargetException {
    return new PrebuiltAppleFramework(
        params,
        resolver,
        new SourcePathResolver(resolver),
        args.framework,
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
        args.supportedPlatformsRegex,
        new Function<CxxPlatform, ImmutableList<String>>() {
          @Override
          public ImmutableList<String> apply(CxxPlatform input) {
            return CxxFlags.getFlags(
                args.exportedLinkerFlags,
                args.exportedPlatformLinkerFlags,
                input);
          }
        }
    );
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
      resolver.requireRule(buildTarget);
      sourcePaths.add(new BuildTargetSourcePath(buildTarget));
      return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
    }
    return Optional.absent();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath framework;
    public Optional<ImmutableSortedSet<FrameworkPath>> frameworks;
    public Optional<Pattern> supportedPlatformsRegex;
    public Optional<ImmutableList<String>> exportedLinkerFlags;
    public Optional<PatternMatchedCollection<ImmutableList<String>>> exportedPlatformLinkerFlags;
  }
}
