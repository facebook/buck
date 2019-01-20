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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class HgCmdLineInterface implements VersionControlCmdLineInterface {

  private static final Logger LOG = Logger.get(VersionControlCmdLineInterface.class);

  private static final Map<String, String> HG_ENVIRONMENT_VARIABLES =
      ImmutableMap.of(
          // Set HGPLAIN to prevent user-defined Hg aliases from interfering with the expected
          // behavior.
          "HGPLAIN", "1");

  private static final Pattern HG_REVISION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

  private static final String HG_CMD_TEMPLATE = "{hg}";
  private static final String REVISION_ID_TEMPLATE = "{revision}";


  private static final ImmutableList<String> CURRENT_REVISION_ID_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "log", "-l", "1", "--template", "{node|short}");

  // -mardu: Track modified, added, deleted, unknown
  private static final ImmutableList<String> CHANGED_FILES_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "status", "-mardu", "-0", "--rev", REVISION_ID_TEMPLATE);

  private static final ImmutableList<String> FAST_STATS_COMMAND =
      ImmutableList.of(
          HG_CMD_TEMPLATE,
          "log",
          "--rev",
          ". + ancestor(.,present(remote/master))",
          "--template",
          "{node|short} {date|hgdate} {remotebookmarks}\\n");

  private ProcessExecutorFactory processExecutorFactory;
  private final Path projectRoot;
  private final String hgCmd;
  private final ImmutableMap<String, String> environment;

  public HgCmdLineInterface(
      ProcessExecutorFactory processExecutorFactory,
      Path projectRoot,
      String hgCmd,
      ImmutableMap<String, String> environment) {
    this.processExecutorFactory = processExecutorFactory;
    this.projectRoot = projectRoot;
    this.hgCmd = hgCmd;
    this.environment = MoreMaps.merge(environment, HG_ENVIRONMENT_VARIABLES);
  }

  @Override
  public boolean isSupportedVersionControlSystem() {
    return true;
  }

  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    return validateRevisionId(executeCommand(CURRENT_REVISION_ID_COMMAND));
  }

  @Override
  public VersionControlSupplier<InputStream> diffBetweenRevisions(
      String baseRevision, String tipRevision) throws VersionControlCommandFailedException {
    validateRevisionId(baseRevision);
    validateRevisionId(tipRevision);

    return () -> {
      try {
        File diffFile = File.createTempFile("diff", ".tmp");
        executeCommand(
            ImmutableList.of(
                HG_CMD_TEMPLATE,
                "export",
                "-o",
                diffFile.toString(),
                "--rev",
                baseRevision + "::" + tipRevision + " - " + baseRevision));
        return new BufferedInputStream(new FileInputStream(diffFile)) {
          @Override
          public void close() throws IOException {
            super.close();
            diffFile.delete();
          }
        };
      } catch (IOException e) {
        LOG.debug(e.getMessage());
        throw new VersionControlCommandFailedException(e.getMessage());
      }
    };
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    String hgChangedFilesString =
        executeCommand(
            replaceTemplateValue(CHANGED_FILES_COMMAND, REVISION_ID_TEMPLATE, fromRevisionId));
    return Arrays.stream(hgChangedFilesString.split("\0"))
        .filter(s -> !s.isEmpty())
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public FastVersionControlStats fastVersionControlStats()
      throws InterruptedException, VersionControlCommandFailedException {
    String output = executeCommand(FAST_STATS_COMMAND, false);
    String[] lines = output.split("\n");
    switch (lines.length) {
      case 1:
        return parseFastStats(lines[0], lines[0]);
      case 2:
        return parseFastStats(lines[0], lines[1]);
    }
    throw new VersionControlCommandFailedException(
        String.format(
            "Unexpected number of lines output from '%s':\n%s",
            String.join(" ", FAST_STATS_COMMAND), output));
  }

  private FastVersionControlStats parseFastStats(
      String currentRevisionLine, String baseRevisionLine)
      throws VersionControlCommandFailedException {
    String numberOfWordsMismatchFormat =
        String.format(
            "Unexpected number of words output from '%s', expected 3 or more:\n%%s",
            String.join(" ", FAST_STATS_COMMAND));
    String[] currentRevisionWords = currentRevisionLine.split(" ", 4);
    if (currentRevisionWords.length < 3) {
      throw new VersionControlCommandFailedException(
          String.format(numberOfWordsMismatchFormat, currentRevisionLine));
    }
    String[] baseRevisionWords = baseRevisionLine.split(" ", 4);
    if (baseRevisionWords.length < 3) {
      throw new VersionControlCommandFailedException(
          String.format(numberOfWordsMismatchFormat, baseRevisionLine));
    }
    return FastVersionControlStats.of(
        currentRevisionWords[0],
        baseRevisionWords.length == 4
            ? ImmutableSet.copyOf(baseRevisionWords[3].split(" "))
            : ImmutableSet.of(),
        baseRevisionWords[0],
        Long.valueOf(baseRevisionWords[1]));
  }

  private String executeCommand(Iterable<String> command)
      throws VersionControlCommandFailedException, InterruptedException {
    return executeCommand(command, true);
  }

  private String executeCommand(Iterable<String> command, boolean cleanOutput)
      throws VersionControlCommandFailedException, InterruptedException {
    command = replaceTemplateValue(command, HG_CMD_TEMPLATE, hgCmd);
    String commandString = commandAsString(command);
    LOG.debug("Executing command: " + commandString);

    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setDirectory(projectRoot)
            .setEnvironment(environment)
            .build();

    ProcessExecutor.Result result;
    try (PrintStream stdout = new PrintStream(new ByteArrayOutputStream());
        PrintStream stderr = new PrintStream(new ByteArrayOutputStream())) {

      ProcessExecutor processExecutor =
          processExecutorFactory.createProcessExecutor(stdout, stderr);

      result = processExecutor.launchAndExecute(processExecutorParams);
    } catch (IOException e) {
      throw new VersionControlCommandFailedException(e);
    }

    Optional<String> resultString = result.getStdout();

    if (!resultString.isPresent()) {
      throw new VersionControlCommandFailedException(
          "Received no output from launched process for command: " + commandString);
    }

    if (result.getExitCode() != 0) {
      throw new VersionControlCommandFailedException(
          result.getMessageForUnexpectedResult(commandString));
    }

    if (cleanOutput) {
      return cleanResultString(resultString.get());
    } else {
      return resultString.get();
    }
  }

  private static String validateRevisionId(String revisionId)
      throws VersionControlCommandFailedException {
    Matcher revisionIdMatcher = HG_REVISION_ID_PATTERN.matcher(revisionId);
    if (!revisionIdMatcher.matches()) {
      throw new VersionControlCommandFailedException(revisionId + " is not a valid revision ID.");
    }
    return revisionId;
  }

  private static Iterable<String> replaceTemplateValue(
      Iterable<String> values, String template, String replacement) {
    return StreamSupport.stream(values.spliterator(), false)
        .map(text -> text.contains(template) ? text.replace(template, replacement) : text)
        .collect(ImmutableList.toImmutableList());
  }

  private static String commandAsString(Iterable<String> command) {
    return Joiner.on(" ").join(command);
  }

  private static String cleanResultString(String result) {
    return result.trim().replace("\'", "").replace("\n", "");
  }
}
