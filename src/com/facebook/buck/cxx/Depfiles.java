/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.ExceptionWithHumanReadableMessage;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/** Specialized parser for .d Makefiles emitted by {@code gcc -MD}. */
class Depfiles {

  private Depfiles() {}

  private enum State {
    LOOKING_FOR_TARGET,
    FOUND_TARGET
  }

  private enum Action {
    NONE,
    APPEND_TO_IDENTIFIER,
    SET_TARGET,
    ADD_PREREQ
  }

  private static final String WHITESPACE_CHARS = " \n\r\t";
  private static final String ESCAPED_TARGET_CHARS = ": #";
  private static final String ESCAPED_PREREQ_CHARS = " #";

  /**
   * Parses the input as a .d Makefile as emitted by {@code gcc -MD} and returns the (target, [dep,
   * dep2, ...]) inside.
   */
  public static Depfile parseDepfile(Readable readable) throws IOException {
    String target = null;
    ImmutableList.Builder<String> prereqsBuilder = ImmutableList.builder();
    State state = State.LOOKING_FOR_TARGET;
    StringBuilder identifierBuilder = new StringBuilder();

    CharBuffer buffer = CharBuffer.allocate(4096);
    int numBackslashes = 0;

    while (readable.read(buffer) != -1) {
      buffer.flip();

      while (buffer.hasRemaining()) {
        char c = buffer.get();
        Action action = Action.NONE;
        boolean isBackslash = c == '\\';
        boolean isCarriageReturn = c == '\r';
        boolean isNewline = c == '\n';
        boolean isWhitespace = WHITESPACE_CHARS.indexOf(c) != -1;
        boolean inIdentifier = identifierBuilder.length() > 0;
        boolean isEscaped;
        if (state == State.LOOKING_FOR_TARGET) {
          isEscaped = ESCAPED_TARGET_CHARS.indexOf(c) != -1;
        } else {
          isEscaped = ESCAPED_PREREQ_CHARS.indexOf(c) != -1;
        }

        if (isBackslash) {
          // We need to count the number of backslashes in case the
          // first non-backslash is an escaped character.
          numBackslashes++;
        } else if (numBackslashes > 0 && isEscaped) {
          // Consume one backslash to escape the special char.
          numBackslashes--;
          if (inIdentifier) {
            action = Action.APPEND_TO_IDENTIFIER;
          }
        } else if (isWhitespace) {
          if (numBackslashes == 0) {
            if (state == State.FOUND_TARGET && inIdentifier) {
              action = Action.ADD_PREREQ;
            }
            if (state == State.FOUND_TARGET && (isNewline || isCarriageReturn)) {
              state = State.LOOKING_FOR_TARGET;
            }
          } else if (isNewline) {
            // Consume one backslash to escape \n or \r\n.
            numBackslashes--;
          } else if (!isCarriageReturn) {
            action = Action.APPEND_TO_IDENTIFIER;
          }
        } else if (c == ':' && state == State.LOOKING_FOR_TARGET) {
          state = State.FOUND_TARGET;
          action = Action.SET_TARGET;
        } else {
          action = Action.APPEND_TO_IDENTIFIER;
        }

        if (!isBackslash && numBackslashes > 0 && !isCarriageReturn) {
          int numBackslashesToAppend;
          if (isEscaped || isWhitespace) {
            // Backslashes escape themselves before an escaped character or whitespace.
            numBackslashesToAppend = numBackslashes / 2;
          } else {
            // Backslashes are literal before a non-escaped character.
            numBackslashesToAppend = numBackslashes;
          }

          for (int i = 0; i < numBackslashesToAppend; i++) {
            identifierBuilder.append('\\');
          }
          numBackslashes = 0;
        }

        switch (action) {
          case NONE:
            break;
          case APPEND_TO_IDENTIFIER:
            identifierBuilder.append(c);
            break;
          case SET_TARGET:
            if (target != null) {
              throw new HumanReadableException(
                  "Depfile parser cannot handle .d file with multiple targets");
            }
            target = identifierBuilder.toString();
            identifierBuilder.setLength(0);
            break;
          case ADD_PREREQ:
            prereqsBuilder.add(identifierBuilder.toString());
            identifierBuilder.setLength(0);
            break;
        }
      }

      buffer.clear();
    }

    ImmutableList<String> prereqs = prereqsBuilder.build();
    if (target == null || prereqs.isEmpty()) {
      throw new IOException("Could not find target or prereqs parsing depfile");
    } else {
      return new Depfile(target, prereqs);
    }
  }

  private static List<String> getRawUsedHeadersFromDepfile(
      ProjectFilesystem filesystem,
      Path sourceDepFile,
      Path inputPath,
      DependencyTrackingMode dependencyTrackingMode)
      throws IOException {
    switch (dependencyTrackingMode) {
      case MAKEFILE:
        try (InputStream input = filesystem.newFileInputStream(sourceDepFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
          ImmutableList<String> prereqs = Depfiles.parseDepfile(reader).getPrereqs();
          // Additional files passed in via command-line flags (e.g. `-fsanitize-blacklist=<file>`)
          // appear first in the dep file, followed by the input source file.  So, just skip over
          // everything until just after the input source which should position us at the headers.
          //
          // TODO(#11303454): This means we're not including the content of these special files into
          // the rule key. The correct way to handle this is likely to support macros in
          // preprocessor/compiler flags at which point we can use the entries for these files in
          // the depfile to verify that the user properly references these files via the macros.
          int inputIndex = prereqs.indexOf(inputPath.toString());
          Preconditions.checkState(
              inputIndex != -1,
              "Could not find input source (%s) in dep file prereqs (%s)",
              inputPath,
              prereqs);
          ImmutableList<String> includes = prereqs.subList(inputIndex + 1, prereqs.size());
          return includes;
        }
      case SHOW_INCLUDES:
        // An intermediate depfile in `show_include` mode contains a source file + used headers
        // (see CxxPreprocessAndCompileStep for details).
        // So, we "strip" the the source file first.
        List<String> srcAndIncludes = filesystem.readLines(sourceDepFile);
        List<String> includes = srcAndIncludes.subList(1, srcAndIncludes.size());
        return includes;
      case NONE:
        return Collections.emptyList();
      default:
        // never happens
        throw new IllegalStateException();
    }
  }

  /**
   * Reads and processes {@code .dep} file produced by a cxx compiler.
   *
   * @param eventBus Used for outputting perf events and messages.
   * @param filesystem Used to access the filesystem and handle String to Path conversion.
   * @param headerPathNormalizer Used to convert raw paths into absolutized paths that can be
   *     resolved to SourcePaths.
   * @param headerVerification Setting for how to respond to untracked header errors.
   * @param sourceDepFile Path to the raw dep file
   * @param inputPath Path to source file input, used to skip any leading entries from {@code
   *     -fsanitize-blacklist}.
   * @param outputPath Path to object file output, used for stat tracking.
   * @param dependencyTrackingMode Setting for how a compiler works with dependencies, used to parse
   *     depfile
   * @return Normalized path objects suitable for use as arguments to {@link
   *     HeaderPathNormalizer#getSourcePathForAbsolutePath(Path)}.
   * @throws IOException if an IO error occurs.
   * @throws HeaderVerificationException if HeaderVerification error occurs and {@code
   *     headerVerification == ERROR}.
   */
  public static ImmutableList<Path> parseAndVerifyDependencies(
      BuckEventBus eventBus,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      HeaderVerification headerVerification,
      Path sourceDepFile,
      Path inputPath,
      Path outputPath,
      DependencyTrackingMode dependencyTrackingMode)
      throws IOException, HeaderVerificationException {
    // Process the dependency file, fixing up the paths, and write it out to it's final location.
    // The paths of the headers written out to the depfile are the paths to the symlinks from the
    // root of the repo if the compilation included them from the header search paths pointing to
    // the symlink trees, or paths to headers relative to the source file if the compilation
    // included them using source relative include paths. To handle both cases we check for the
    // prerequisites both in the values and the keys of the replacement map.
    Logger.get(Depfiles.class).debug("Processing dependency file %s as Makefile", sourceDepFile);
    try (SimplePerfEvent.Scope perfEvent =
        SimplePerfEvent.scope(
            eventBus,
            PerfEventId.of("depfile-parse"),
            ImmutableMap.of("input", inputPath, "output", outputPath))) {

      List<String> headers =
          getRawUsedHeadersFromDepfile(
              filesystem, sourceDepFile, inputPath, dependencyTrackingMode);

      return normalizeAndVerifyHeaders(
          eventBus, filesystem, headerPathNormalizer, headerVerification, inputPath, headers);
    }
  }

  private static ImmutableList<Path> normalizeAndVerifyHeaders(
      BuckEventBus eventBus,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      HeaderVerification headerVerification,
      Path inputPath,
      List<String> headers)
      throws IOException, HeaderVerificationException {
    ImmutableList.Builder<Path> resultBuilder = ImmutableList.builder();
    for (String rawHeader : headers) {
      Path header = filesystem.resolve(rawHeader).normalize();
      Optional<Path> absolutePath = headerPathNormalizer.getAbsolutePathForUnnormalizedPath(header);
      Optional<Path> repoRelativePath = filesystem.getPathRelativeToProjectRoot(header);
      if (absolutePath.isPresent()) {
        Preconditions.checkState(absolutePath.get().isAbsolute());
        resultBuilder.add(absolutePath.get());
      } else if ((headerVerification.getMode() != HeaderVerification.Mode.IGNORE)
          && (!(headerVerification.isWhitelisted(header.toString())
              || repoRelativePath
                  .map(path -> headerVerification.isWhitelisted(path.toString()))
                  .orElse(false)))) {
        // Check again with the real path with all symbolic links resolved.
        header = header.toRealPath();
        if (!(headerVerification.isWhitelisted(header.toString()))) {
          String errorMessage =
              String.format(
                  "%s: included an untracked header \"%s\"\n\n"
                      + "Please reference this header file from \"headers\", \"exported_headers\" or \"raw_headers\" \n"
                      + "in the appropriate build rule.",
                  inputPath, repoRelativePath.orElse(header));
          eventBus.post(
              ConsoleEvent.create(
                  headerVerification.getMode() == HeaderVerification.Mode.ERROR
                      ? Level.SEVERE
                      : Level.WARNING,
                  errorMessage));
          if (headerVerification.getMode() == HeaderVerification.Mode.ERROR) {
            throw new HeaderVerificationException(errorMessage);
          }
        }
      }
    }
    return resultBuilder.build();
  }

  public static class Depfile {

    private final String target;
    private final ImmutableList<String> prereqs;

    public Depfile(String target, ImmutableList<String> prereqs) {
      this.target = target;
      this.prereqs = prereqs;
    }

    public ImmutableList<String> getPrereqs() {
      return prereqs;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Depfile depfile = (Depfile) o;
      return Objects.equals(target, depfile.target) && Objects.equals(prereqs, depfile.prereqs);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, prereqs);
    }

    @Override
    public String toString() {
      return String.format("%s target=%s prereqs=%s", super.toString(), target, prereqs);
    }
  }

  public static class HeaderVerificationException extends Exception
      implements ExceptionWithHumanReadableMessage {

    public HeaderVerificationException(String message) {
      super(message);
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getLocalizedMessage();
    }
  }
}
