/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.RuleKeyAppendable;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.WeakMemoizer;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Helper class for handling preprocessing related tasks of a cxx compilation rule. */
@CustomClassBehavior(PreprocessorDelegate.SerializationBehavior.class)
final class PreprocessorDelegate implements RuleKeyAppendable, HasCustomDepsLogic {

  // Fields that are added to rule key as is.
  @AddToRuleKey private final Preprocessor preprocessor;

  @AddToRuleKey
  private final RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction;

  @AddToRuleKey private final HeaderVerification headerVerification;

  // Fields that added to the rule key with some processing.
  @AddToRuleKey private final PreprocessorFlags preprocessorFlags;

  @AddToRuleKey private final ImmutableSortedSet<String> conflictingHeadersBasenameWhitelist;

  // Fields that are not added to the rule key.
  private final PathSourcePath workingDir;

  /**
   * If present, these paths will be added first (prior to the current rule's list of paths) when
   * building the list of compiler flags, in {@link #getFlagsWithSearchPaths(Optional,
   * SourcePathResolver)}.
   */
  private final Optional<CxxIncludePaths> leadingIncludePaths;

  private final PathShortener minLengthPathRepresentation;

  private final Optional<BuildRule> aggregatedDeps;

  private final WeakMemoizer<HeaderPathNormalizer> headerPathNormalizer = new WeakMemoizer<>();

  private final Supplier<Optional<ConflictingHeadersResult>> lazyConflictingHeadersCheckResult =
      Suppliers.memoize(this::checkConflictingHeadersUncached);

  public PreprocessorDelegate(
      HeaderVerification headerVerification,
      PathSourcePath workingDir,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessorFlags,
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction,
      Optional<CxxIncludePaths> leadingIncludePaths,
      Optional<BuildRule> aggregatedDeps,
      ImmutableSortedSet<String> conflictingHeaderBasenameWhitelist) {
    this.preprocessor = preprocessor;
    this.preprocessorFlags = preprocessorFlags;
    this.headerVerification = headerVerification;
    this.workingDir = workingDir;
    this.minLengthPathRepresentation = PathShortener.byRelativizingToWorkingDir(workingDir);
    this.frameworkPathSearchPathFunction = frameworkPathSearchPathFunction;
    this.leadingIncludePaths = leadingIncludePaths;
    this.aggregatedDeps = aggregatedDeps;
    this.conflictingHeadersBasenameWhitelist = conflictingHeaderBasenameWhitelist;
  }

  public PreprocessorDelegate withLeadingIncludePaths(CxxIncludePaths leadingIncludePaths) {
    return new PreprocessorDelegate(
        this.headerVerification,
        this.workingDir,
        this.preprocessor,
        this.preprocessorFlags,
        this.frameworkPathSearchPathFunction,
        Optional.of(leadingIncludePaths),
        this.aggregatedDeps,
        conflictingHeadersBasenameWhitelist);
  }

  public Preprocessor getPreprocessor() {
    return preprocessor;
  }

  public HeaderPathNormalizer getHeaderPathNormalizer(BuildContext context) {
    return headerPathNormalizer.get(
        () -> {
          try (Scope ignored = LeafEvents.scope(context.getEventBus(), "header_path_normalizer")) {
            // Cache the value using the first SourcePathResolver that we're called with. We expect
            // this whole object to be recreated in cases where this computation would produce
            // different results.
            HeaderPathNormalizer.Builder builder =
                new HeaderPathNormalizer.Builder(context.getSourcePathResolver());
            for (CxxHeaders include : preprocessorFlags.getIncludes()) {
              include.addToHeaderPathNormalizer(builder);
            }
            for (FrameworkPath frameworkPath : preprocessorFlags.getFrameworkPaths()) {
              frameworkPath.getSourcePath().ifPresent(builder::addHeaderDir);
            }
            if (preprocessorFlags.getPrefixHeader().isPresent()) {
              SourcePath headerPath = preprocessorFlags.getPrefixHeader().get();
              builder.addPrefixHeader(headerPath);
            }
            return builder.build();
          }
        });
  }

  /**
   * Get the command for standalone preprocessor calls.
   *
   * @param compilerFlags flags to append.
   * @param pch
   */
  public ImmutableList<Arg> getCommand(
      CxxToolFlags compilerFlags,
      Optional<PrecompiledHeaderData> pch,
      SourcePathResolver resolver) {
    return ImmutableList.<Arg>builder()
        .addAll(StringArg.from(getCommandPrefix(resolver)))
        .addAll(getArguments(compilerFlags, pch, resolver))
        .build();
  }

  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return preprocessor.getCommandPrefix(resolver);
  }

  public ImmutableList<Arg> getArguments(
      CxxToolFlags compilerFlags,
      Optional<PrecompiledHeaderData> pch,
      SourcePathResolver resolver) {
    return ImmutableList.copyOf(
        CxxToolFlags.concat(getFlagsWithSearchPaths(pch, resolver), compilerFlags).getAllFlags());
  }

  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return preprocessor.getEnvironment(resolver);
  }

  public CxxToolFlags getFlagsWithSearchPaths(
      Optional<PrecompiledHeaderData> pch, SourcePathResolver resolver) {
    CxxToolFlags leadingFlags;
    if (leadingIncludePaths.isPresent()) {
      leadingFlags =
          leadingIncludePaths
              .get()
              .toToolFlags(
                  resolver,
                  minLengthPathRepresentation,
                  frameworkPathSearchPathFunction,
                  preprocessor);
    } else {
      leadingFlags = CxxToolFlags.of();
    }

    return CxxToolFlags.concat(
        leadingFlags,
        preprocessorFlags.toToolFlags(
            resolver,
            minLengthPathRepresentation,
            frameworkPathSearchPathFunction,
            preprocessor,
            pch));
  }

  /**
   * Get all the preprocessor's include paths.
   *
   * @see PreprocessorFlags#getCxxIncludePaths()
   */
  public CxxIncludePaths getCxxIncludePaths() {
    return preprocessorFlags.getCxxIncludePaths();
  }

  public CxxToolFlags getNonIncludePathFlags(SourcePathResolver resolver) {
    return preprocessorFlags.getNonIncludePathFlags(resolver, Optional.empty(), preprocessor);
  }

  /**
   * Build a {@link CxxToolFlags} representing our include paths (local, system, iquote, framework).
   * Does not include {@link #leadingIncludePaths}.
   */
  public CxxToolFlags getIncludePathFlags(SourcePathResolver resolver) {
    return preprocessorFlags.getIncludePathFlags(
        resolver, minLengthPathRepresentation, frameworkPathSearchPathFunction, preprocessor);
  }

  /**
   * Build a {@link CxxToolFlags} representing our sanitized include paths (local, system, iquote,
   * framework). Does not include {@link #leadingIncludePaths}.
   *
   * @param sanitizer
   */
  public CxxToolFlags getSanitizedIncludePathFlags(
      SourcePathResolver resolver, DebugPathSanitizer sanitizer) {
    return preprocessorFlags.getSanitizedIncludePathFlags(
        sanitizer,
        resolver,
        minLengthPathRepresentation,
        frameworkPathSearchPathFunction,
        preprocessor);
  }

  /** @see SupportsDependencyFileRuleKey */
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      Iterable<Path> dependencies, BuildContext context) {
    Stream.Builder<SourcePath> inputsBuilder = Stream.builder();

    // Add inputs that we always use.
    BuildableSupport.deriveInputs(preprocessor).forEach(inputsBuilder);

    // Prefix header is not represented in the dep file, so should be added manually.
    if (preprocessorFlags.getPrefixHeader().isPresent()) {
      inputsBuilder.add(preprocessorFlags.getPrefixHeader().get());
    }

    // Args can contain things like location macros, so extract any inputs we find.
    for (Arg arg : preprocessorFlags.getOtherFlags().getAllFlags()) {
      BuildableSupport.deriveInputs(arg).forEach(inputsBuilder);
    }

    // Add any header/include inputs that our dependency file said we used.
    //
    // TODO(#9117006): We need to find out which `SourcePath` each line in the dep file refers to.
    // Since we force our compilation process to refer to headers using relative paths,
    // which results in dep files containing relative paths, we can't always get this 100%
    // correct (e.g. there may be two `SourcePath` includes with the same relative path, but
    // coming from different cells).  Favor correctness in this case and just add *all*
    // `SourcePath`s that have relative paths matching those specific in the dep file.
    HeaderPathNormalizer headerPathNormalizer = getHeaderPathNormalizer(context);
    for (Path absolutePath : dependencies) {
      Preconditions.checkState(absolutePath.isAbsolute());
      inputsBuilder.add(headerPathNormalizer.getSourcePathForAbsolutePath(absolutePath));
    }

    return inputsBuilder
        .build()
        .filter(getCoveredByDepFilePredicate())
        .collect(ImmutableList.toImmutableList());
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    // TODO(jkeljo): I didn't know how to implement this, and didn't have time to figure it out.
    // TODO(cjhopman): This should only include paths from the headers, not all the tools and other
    // random things added to the rulekeys.
    return (SourcePath path) ->
        !(path instanceof PathSourcePath)
            || !((PathSourcePath) path).getRelativePath().isAbsolute();
  }

  public HeaderVerification getHeaderVerification() {
    return headerVerification;
  }

  public Optional<SourcePath> getPrefixHeader() {
    return preprocessorFlags.getPrefixHeader();
  }

  public PreprocessorFlags getPreprocessorFlags() {
    return preprocessorFlags;
  }

  /**
   * Returns whether there are conflicting headers in the includes given to this object.
   *
   * <p>Conflicting headers are different header files that can be accessed by the same include
   * directive. In a C++ compiler, the header that appears in the first header search path is used,
   * but in buck, we mandate that there are no conflicts at all.
   *
   * <p>Since buck manages the header search paths of a library based on its transitive
   * dependencies, this constraint prevents spooky behavior where a dependency change may affect
   * header resolution of far-away libraries. It also enables buck to perform various optimizations
   * that would be impossible if it has to respect header search path ordering.
   */
  public Optional<ConflictingHeadersResult> checkConflictingHeaders() {
    return lazyConflictingHeadersCheckResult.get();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {}

  @Override
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    Preconditions.checkState(aggregatedDeps.isPresent());
    return new DepsBuilder(ruleFinder).add(aggregatedDeps.get()).add(this).build().stream();
  }

  private Optional<ConflictingHeadersResult> checkConflictingHeadersUncached() {
    Iterable<CxxHeaders> allHeaders = preprocessorFlags.getIncludes();
    int estimatedSize =
        RichStream.from(allHeaders)
            .filter(CxxSymlinkTreeHeaders.class)
            .mapToInt(cxxHeaders -> cxxHeaders.getNameToPathMap().size())
            .sum();
    Map<Path, SourcePath> headers = new HashMap<>(estimatedSize);
    for (CxxHeaders cxxHeaders : allHeaders) {
      if (cxxHeaders instanceof CxxSymlinkTreeHeaders) {
        CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) cxxHeaders;
        for (Map.Entry<Path, SourcePath> entry : symlinkTreeHeaders.getNameToPathMap().entrySet()) {
          if (entry.getKey().getFileName() != null
              && conflictingHeadersBasenameWhitelist.contains(
                  entry.getKey().getFileName().toString())) {
            continue;
          }
          SourcePath original = headers.put(entry.getKey(), entry.getValue());
          if (original != null && !original.equals(entry.getValue())) {
            return Optional.of(
                new ConflictingHeadersResult(entry.getKey(), original, entry.getValue()));
          }
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Result of a conflicting headers check.
   *
   * @see #checkConflictingHeaders()
   */
  public static class ConflictingHeadersResult {
    private final Path includeFilePath;
    private final SourcePath headerPath1;
    private final SourcePath headerPath2;

    private ConflictingHeadersResult(
        Path includeFilePath, SourcePath headerPath1, SourcePath headerPath2) {
      this.includeFilePath = includeFilePath;
      this.headerPath1 = headerPath1;
      this.headerPath2 = headerPath2;
    }

    /** Throw an exception with a user friendly message detailing the conflict. */
    public HumanReadableException throwHumanReadableExceptionWithContext(BuildTarget buildTarget) {
      throw new HumanReadableException(
          "Target '%s' has dependencies using headers that can be included using the same path.\n\n"
              + "'%s' maps to the following header files:\n"
              + "- %s\n"
              + "- and %s\n\n"
              + "Please rename one of them or export one of them to a different path.",
          buildTarget, includeFilePath, headerPath1, headerPath2);
    }
  }

  /** Custom serialization. */
  static class SerializationBehavior implements CustomClassSerialization<PreprocessorDelegate> {
    static final ValueTypeInfo<Preprocessor> PREPROCESSOR_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Preprocessor>() {});
    static final ValueTypeInfo<RuleKeyAppendableFunction<FrameworkPath, Path>>
        FRAMEWORK_PATH_FUNCTION_TYPE_INFO =
            ValueTypeInfoFactory.forTypeToken(
                new TypeToken<RuleKeyAppendableFunction<FrameworkPath, Path>>() {});
    static final ValueTypeInfo<HeaderVerification> HEADER_VERIFICATION_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<HeaderVerification>() {});
    static final ValueTypeInfo<PreprocessorFlags> PREPROCESSOR_FLAGS_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<PreprocessorFlags>() {});

    @Override
    public <E extends Exception> void serialize(
        PreprocessorDelegate instance, ValueVisitor<E> serializer) throws E {
      PREPROCESSOR_TYPE_INFO.visit(instance.preprocessor, serializer);
      FRAMEWORK_PATH_FUNCTION_TYPE_INFO.visit(instance.frameworkPathSearchPathFunction, serializer);
      HEADER_VERIFICATION_TYPE_INFO.visit(instance.headerVerification, serializer);
      PREPROCESSOR_FLAGS_TYPE_INFO.visit(instance.preprocessorFlags, serializer);
      serializer.visitSourcePath(instance.workingDir);
      serializer.visitInteger(instance.conflictingHeadersBasenameWhitelist.size());
      RichStream.from(instance.conflictingHeadersBasenameWhitelist)
          .forEachThrowing(serializer::visitString);
      Preconditions.checkState(
          !instance.leadingIncludePaths.isPresent(), "leadingIncludePaths is not serializable.");
    }

    @Override
    public <E extends Exception> PreprocessorDelegate deserialize(ValueCreator<E> deserializer)
        throws E {
      Preprocessor preprocessor = PREPROCESSOR_TYPE_INFO.createNotNull(deserializer);
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction =
          FRAMEWORK_PATH_FUNCTION_TYPE_INFO.createNotNull(deserializer);
      HeaderVerification headerVerification =
          HEADER_VERIFICATION_TYPE_INFO.createNotNull(deserializer);
      PreprocessorFlags preprocessorFlags =
          PREPROCESSOR_FLAGS_TYPE_INFO.createNotNull(deserializer);
      SourcePath workingDirSourcePath = deserializer.createSourcePath();
      Preconditions.checkState(workingDirSourcePath instanceof PathSourcePath);
      PathSourcePath workingDir = (PathSourcePath) workingDirSourcePath;
      ImmutableSortedSet.Builder<String> conflictingHeadersBasenameWhitelistBuilder =
          ImmutableSortedSet.naturalOrder();
      int conflictingHeadersBasenameWhitelistSize = deserializer.createInteger();
      for (int i = 0; i < conflictingHeadersBasenameWhitelistSize; i++) {
        conflictingHeadersBasenameWhitelistBuilder.add(deserializer.createString());
      }
      ImmutableSortedSet<String> conflictingHeadersBasenameWhitelist =
          conflictingHeadersBasenameWhitelistBuilder.build();
      return new PreprocessorDelegate(
          headerVerification,
          workingDir,
          preprocessor,
          preprocessorFlags,
          frameworkPathSearchPathFunction,
          Optional.empty(),
          Optional.empty(),
          conflictingHeadersBasenameWhitelist);
    }
  }
}
