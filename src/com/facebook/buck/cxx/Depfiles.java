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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Specialized parser for .d Makefiles emitted by {@code gcc -MD}.
 */
public class Depfiles {

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
   * Parses the input as a .d Makefile as emitted by {@code gcc -MD}
   * and returns the (target, [dep, dep2, ...]) inside.
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

  public static int parseAndWriteBuckCompatibleDepfile(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      HeaderVerification headerVerification,
      Path sourceDepFile,
      Path destDepFile,
      Path inputPath,
      Path outputPath
  ) throws IOException {
    // Process the dependency file, fixing up the paths, and write it out to it's final location.
    // The paths of the headers written out to the depfile are the paths to the symlinks from the
    // root of the repo if the compilation included them from the header search paths pointing to
    // the symlink trees, or paths to headers relative to the source file if the compilation
    // included them using source relative include paths. To handle both cases we check for the
    // prerequisites both in the values and the keys of the replacement map.
    Logger.get(Depfiles.class).debug("Processing dependency file %s as Makefile", sourceDepFile);
    ImmutableMap<String, Object> params = ImmutableMap.<String, Object>of(
        "input", inputPath, "output", outputPath);
    try (InputStream input = filesystem.newFileInputStream(sourceDepFile);
         BufferedReader reader = new BufferedReader(new InputStreamReader(input));
         OutputStream output = filesystem.newFileOutputStream(destDepFile);
         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
         SimplePerfEvent.Scope perfEvent = SimplePerfEvent.scope(
             context.getBuckEventBus(),
             PerfEventId.of("depfile-parse"),
             params)) {
      ImmutableList<String> prereqs = Depfiles.parseDepfile(reader).getPrereqs();
      // Skip the first prereq, as it's the input source file.
      Preconditions.checkState(inputPath.toString().equals(prereqs.get(0)));
      ImmutableList<String> headers = prereqs.subList(1, prereqs.size());
      for (String header : headers) {
        Optional<Path> absolutePath =
            headerPathNormalizer.getAbsolutePathForUnnormalizedPath(Paths.get(header));
        if (absolutePath.isPresent()) {
          Preconditions.checkState(absolutePath.get().isAbsolute());
          writer.write(absolutePath.get().toString());
          writer.newLine();
        } else if (
            headerVerification.getMode() != HeaderVerification.Mode.IGNORE &&
                !headerVerification.isWhitelisted(header)) {
          context.getBuckEventBus().post(
              ConsoleEvent.create(
                  headerVerification.getMode() == HeaderVerification.Mode.ERROR ?
                      Level.SEVERE :
                      Level.WARNING,
                  "%s: included an untracked header \"%s\"",
                  inputPath,
                  header));
          if (headerVerification.getMode() == HeaderVerification.Mode.ERROR) {
            return 1;
          }
        }
      }
    }
    return 0;
  }

  public static class Depfile {

    private final String target;
    private final ImmutableList<String> prereqs;

    public Depfile(String target, ImmutableList<String> prereqs) {
      this.target = target;
      this.prereqs = prereqs;
    }

    public String getTarget() {
      return target;
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
}
