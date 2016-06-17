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
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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
  private final HeaderVerification headerVerification;

  private final Function<Path, Path> minLengthPathRepresentation =
      new Function<Path, Path>() {
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

  private final Supplier<HeaderPathNormalizer> headerPathNormalizer =
      MoreSuppliers.weakMemoize(
          new Supplier<HeaderPathNormalizer>() {
            @Override
            public HeaderPathNormalizer get() {
              HeaderPathNormalizer.Builder builder =
                  new HeaderPathNormalizer.Builder(
                      resolver,
                      minLengthPathRepresentation);
              for (CxxHeaders include : includes) {
                include.addToHeaderPathNormalizer(builder);
              }
              return builder.build();
            }
          });


  public PreprocessorDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      HeaderVerification headerVerification,
      Path workingDir,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessorFlags,
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction,
      List<CxxHeaders> includes) {
    this.preprocessor = preprocessor;
    this.includes = ImmutableList.copyOf(includes);
    this.preprocessorFlags = preprocessorFlags;
    this.sanitizer = sanitizer;
    this.headerVerification = headerVerification;
    this.workingDir = workingDir;
    this.resolver = resolver;
    this.frameworkPathSearchPathFunction = frameworkPathSearchPathFunction;
  }

  public Preprocessor getPreprocessor() {
    return preprocessor;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("preprocessor", preprocessor);
    sink.setReflectively("includes", includes);
    sink.setReflectively("frameworkPathSearchPathFunction", frameworkPathSearchPathFunction);
    sink.setReflectively("headerVerification", headerVerification);
    preprocessorFlags.appendToRuleKey(sink, sanitizer);
  }

  public HeaderPathNormalizer getHeaderPathNormalizer() {
    return headerPathNormalizer.get();
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

  public void checkForConflictingHeaders() throws ConflictingHeadersException {
    Map<Path, SourcePath> headers = new HashMap<>();
    for (CxxHeaders cxxHeaders : includes) {
      if (cxxHeaders instanceof CxxSymlinkTreeHeaders) {
        CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) cxxHeaders;
        for (Map.Entry<Path, SourcePath> entry : symlinkTreeHeaders.getNameToPathMap().entrySet()) {
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
  }

  /**
   * @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey
   */
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(Iterable<String> depFileLines) {
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
    HeaderPathNormalizer headerPathNormalizer = getHeaderPathNormalizer();
    for (String line : depFileLines) {
      Path absolutePath = Paths.get(line);
      Preconditions.checkState(absolutePath.isAbsolute());
      inputs.add(headerPathNormalizer.getSourcePathForAbsolutePath(Paths.get(line)));
    }

    return inputs.build();
  }

  public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
    // TODO(jkeljo): I didn't know how to implement this, and didn't have time to figure it out.
    return Optional.absent();
  }

  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    return preprocessor.getFlagsForColorDiagnostics();
  }

  public HeaderVerification getHeaderVerification() {
    return headerVerification;
  }

  public Optional<SourcePath> getPrefixHeader() {
    return preprocessorFlags.getPrefixHeader();
  }

  /**
   * Generate a digest of the compiler flags.
   *
   * Generated PCH files can only be used when compiling with similar compiler flags. This
   * guarantees the uniqueness of the generated file.
   */
  public String hashCommand(CxxToolFlags extraFlags) {
    Hasher hasher = Hashing.murmur3_128().newHasher();
    String workingDirString = workingDir.toString();
    // Skips the executable argument (the first one) as that is not sanitized.
    for (String part : sanitizer.sanitizeFlags(Iterables.skip(getCommand(extraFlags), 1))) {
      // TODO(#10251354): find a better way of dealing with getting a project dir normalized hash
      if (part.startsWith(workingDirString)) {
        part = "<WORKINGDIR>" + part.substring(workingDirString.length());
      }
      hasher.putString(part, Charsets.UTF_8);
      hasher.putBoolean(false); // separator
    }
    return hasher.hash().toString();
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

}
