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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class for handling preprocessing related tasks of a cxx compilation rule.
 */
class PreprocessorDelegate implements RuleKeyAppendable {

  // Fields that are added to rule key as is.
  private final Preprocessor preprocessor;
  private final ImmutableList<CxxHeaders> includes;
  private final RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction;

  // Fields that added to the rule key with some processing.
  private final PreprocessorFlags preprocessorFlags;

  // Fields that are not added to the rule key.
  private final DebugPathSanitizer sanitizer;
  private final Path workingDir;
  private final SourcePathResolver resolver;
  private final Function<Path, Path> minLengthPathRepresentation = new Function<Path, Path>() {
    @Override
    public Path apply(Path path) {
      Preconditions.checkState(
          path.isAbsolute(),
          "Expected preprocessor suffix to be absolute: %s",
          path);
      String absoluteString = path.toString();
      Path relativePath = MorePaths.relativize(workingDir, path);
      String relativeString = relativePath.toString();
      return absoluteString.length() > relativeString.length() ? relativePath : path;
    }
  };
  private final Supplier<ImmutableMap<Path, Path>> replacementPaths;
  private final Supplier<ImmutableMultimap<String, SourcePath>> pathToSourcePathMap;

  public PreprocessorDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      Path workingDir,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessorFlags,
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction,
      List<CxxHeaders> includes) {
    this.preprocessor = preprocessor;
    this.includes = ImmutableList.copyOf(includes);
    this.preprocessorFlags = preprocessorFlags;
    this.sanitizer = sanitizer;
    this.workingDir = workingDir;
    this.resolver = resolver;
    this.frameworkPathSearchPathFunction = frameworkPathSearchPathFunction;

    this.replacementPaths = MoreSuppliers.weakMemoize(new ReplacementPathsSupplier());
    this.pathToSourcePathMap = MoreSuppliers.weakMemoize(new PathToSourcePathMapSupplier());
  }

  public Preprocessor getPreprocessor() {
    return preprocessor;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("preprocessor", preprocessor);
    builder.setReflectively("includes", includes);
    builder.setReflectively("frameworkPathSearchPathFunction", frameworkPathSearchPathFunction);
    preprocessorFlags.appendToRuleKey(builder, sanitizer);
    return builder;
  }

  /**
   * Resolve the map of symlinks to real paths to hand off the preprocess step.
   */
  public ImmutableMap<Path, Path> getReplacementPaths() {
    return replacementPaths.get();
  }

  /**
   * Get the command for standalone preprocessor calls.
   *
   * @param compilerFlags flags to append.
   */
  public ImmutableList<String> getCommand(CxxToolFlags compilerFlags) {
    return ImmutableList.<String>builder()
        .addAll(preprocessor.getCommandPrefix(resolver))
        .addAll(CxxToolFlags.concat(getFlagsWithSearchPaths(), compilerFlags).getAllFlags())
        .addAll(preprocessor.getExtraFlags().or(ImmutableList.<String>of()))
        .build();
  }

  public ImmutableMap<String, String> getEnvironment() {
    return preprocessor.getEnvironment(resolver);
  }

  public CxxToolFlags getFlagsWithSearchPaths() {
    return preprocessorFlags.toToolFlags(
        resolver,
        minLengthPathRepresentation,
        frameworkPathSearchPathFunction);
  }

  /**
   * Get custom line processor for preprocessor output for use when running the step.
   */
  public Optional<Function<String, Iterable<String>>> getPreprocessorExtraLineProcessor() {
    return preprocessor.getExtraLineProcessor();
  }

  public void checkForConflictingHeaders() throws ConflictingHeadersException {
    Map<Path, SourcePath> headers = new HashMap<>();
    for (CxxHeaders cxxHeaders : includes) {
      for (Map.Entry<Path, SourcePath> entry : cxxHeaders.getNameToPathMap().entrySet()) {
        SourcePath original = headers.put(entry.getKey(), entry.getValue());
        if (original != null && !original.equals(entry.getValue())) {
          throw new ConflictingHeadersException(
              entry.getKey(),
              original,
              entry.getValue());
        }
      }
    }
  }

  /**
   * @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey
   */
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(List<String> depFileLines) {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // Add inputs that we always use.
    inputs.addAll(preprocessor.getInputs());

    // Prefix header is not represented in the dep file, so should be added manually.
    if (preprocessorFlags.getPrefixHeader().isPresent()) {
      inputs.add(preprocessorFlags.getPrefixHeader().get());
    }

    // Add any header/include inputs that our dependency file said we used.
    //
    // TODO(#9117006): We need to find out which `SourcePath` each line in the dep file refers to.
    // Since we force our compilation process to refer to headers using relative paths,
    // which results in dep files containing relative paths, we can't always get this 100%
    // correct (e.g. there may be two `SourcePath` includes with the same relative path, but
    // coming from different cells).  Favor correctness in this case and just add *all*
    // `SourcePath`s that have relative paths matching those specific in the dep file.
    ImmutableMultimap<String, SourcePath> pathToSourcePathMap = this.pathToSourcePathMap.get();
    for (String line : depFileLines) {
      inputs.addAll(pathToSourcePathMap.get(line));
    }

    return inputs.build();
  }

  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    return preprocessor.getFlagsForColorDiagnostics();
  }

  @SuppressWarnings("serial")
  public static class ConflictingHeadersException extends Exception {
    public ConflictingHeadersException(Path key, SourcePath value1, SourcePath value2) {
      super(
          String.format(
              "'%s' maps to both %s.",
              key,
              ImmutableSortedSet.of(value1, value2)));
    }

    public HumanReadableException getHumanReadableExceptionForBuildTarget(BuildTarget buildTarget) {
      return new HumanReadableException(
          this,
          "Target '%s' uses conflicting header file mappings. %s",
          buildTarget,
          getMessage());
    }
  }

  private class ReplacementPathsSupplier implements Supplier<ImmutableMap<Path, Path>> {
    @Override
    public ImmutableMap<Path, Path> get() {
      // Use a regular map here, as we might have duplicate entries.
      Map<Path, Path> replacementPathsBuilder = new LinkedHashMap<>();
      for (CxxHeaders include : includes) {
        Path root = resolver.getAbsolutePath(include.getRoot());
        for (Map.Entry<Path, SourcePath> entry : include.getNameToPathMap().entrySet()) {
          // TODO(#9117006): We don't currently support a way to serialize `SourcePath` objects in a
          // cache-compatible format, and we certainly can't use absolute paths here.  So, for now,
          // just use relative paths.  The consequence here is that debug paths and error/warning
          // messages may be incorrect when referring to headers in another cell.
          replacementPathsBuilder.put(
              Preconditions.checkNotNull(
                  minLengthPathRepresentation.apply(root.resolve(entry.getKey()))),
              resolver.getRelativePath(entry.getValue()));
        }
      }
      return ImmutableMap.copyOf(replacementPathsBuilder);
    }
  }

  private class PathToSourcePathMapSupplier
      implements Supplier<ImmutableMultimap<String, SourcePath>> {
    @Override
    public ImmutableMultimap<String, SourcePath> get() {
      List<SourcePath> paths = new ArrayList<>();
      for (CxxHeaders cxxHeaders : includes) {
        paths.addAll(cxxHeaders.getNameToPathMap().values());
      }
      return Multimaps.index(
          paths,
          Functions.compose(
              Functions.toStringFunction(),
              resolver.getRelativePathFunction()));
    }
  }

}
