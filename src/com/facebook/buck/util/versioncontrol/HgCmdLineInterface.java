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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class HgCmdLineInterface implements VersionControlCmdLineInterface {

  private static final Logger LOG = Logger.get(VersionControlCmdLineInterface.class);

  private static final String HG_ROOT_PATH = ".hg";
  private static final String REMOTE_NAMES_FILENAME = "remotenames";

  private static final Map<String, String> HG_ENVIRONMENT_VARIABLES = ImmutableMap.of(
      // Set HGPLAIN to prevent user-defined Hg aliases from interfering with the expected behavior.
      "HGPLAIN", "1"
  );
  private static final Pattern HG_REVISION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");
  private static final Pattern HG_DATE_PATTERN = Pattern.compile("(\\d+)\\s([\\-\\+]?\\d+)");
  private static final int HG_UNIX_TS_GROUP_INDEX = 1;

  private static final String HG_CMD_TEMPLATE = "{hg}";
  private static final String NAME_TEMPLATE = "{name}";
  private static final String REVISION_ID_TEMPLATE = "{revision}";
  private static final String REVISION_IDS_TEMPLATE = "{revisions}";

  private static final ImmutableList<String> HG_ROOT_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "root");

  private static final ImmutableList<String> CURRENT_REVISION_ID_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "log", "-l", "1", "--template", "{node|short}");

  private static final ImmutableList<String> REVISION_ID_FOR_NAME_COMMAND_TEMPLATE =
      ImmutableList.of(HG_CMD_TEMPLATE, "log", "-r", NAME_TEMPLATE, "--template", "{node|short}");

  // -mardu: Track modified, added, deleted, unknown
  private static final ImmutableList<String> CHANGED_FILES_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "status", "-mardu", "-0", "--rev", REVISION_ID_TEMPLATE);

  private static final ImmutableList<String> COMMON_ANCESTOR_COMMAND_TEMPLATE =
      ImmutableList.of(
          HG_CMD_TEMPLATE,
          "log",
          "--rev",
          "ancestor(" + REVISION_IDS_TEMPLATE + ")",
          "--template",
          "'{node|short}'");

  private static final ImmutableList<String> REVISION_AGE_COMMAND =
      ImmutableList.of(
          HG_CMD_TEMPLATE,
          "log",
          "-r",
          REVISION_ID_TEMPLATE,
          "--template",
          "'{date|hgdate}'");

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
    return true; // Mercurial is supported
  }

  @Override
  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException  {
    return validateRevisionId(executeCommand(CURRENT_REVISION_ID_COMMAND));
  }

  @Override
  public String revisionId(String name)
      throws VersionControlCommandFailedException, InterruptedException {
    return validateRevisionId(
        executeCommand(
            replaceTemplateValue(
                REVISION_ID_FOR_NAME_COMMAND_TEMPLATE,
                NAME_TEMPLATE,
                name)));
  }

  @Override
  public Optional<String> revisionIdOrAbsent(String name) throws InterruptedException {
    try {
      return Optional.of(revisionId(name));
    } catch (VersionControlCommandFailedException e) {
      return Optional.empty();
    }
  }

  @Override
  public String commonAncestor(String revisionIdOne, String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException {
    return validateRevisionId(
        executeCommand(
            replaceTemplateValue(
                COMMON_ANCESTOR_COMMAND_TEMPLATE,
                REVISION_IDS_TEMPLATE,
                (revisionIdOne + "," + revisionIdTwo))));
  }

  @Override
  public Optional<String> commonAncestorOrAbsent(String revisionIdOne, String revisionIdTwo)
      throws InterruptedException {
    try {
      return Optional.of(commonAncestor(revisionIdOne, revisionIdTwo));
    } catch (VersionControlCommandFailedException e) {
      return Optional.empty();
    }
  }

  @Override
  public String diffBetweenRevisions(String revisionIdOne, String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException {
    validateRevisionId(revisionIdOne);
    validateRevisionId(revisionIdTwo);
    return executeCommand(
        ImmutableList.of(
            HG_CMD_TEMPLATE,
            "diff",
            "--rev",
            revisionIdOne,
            "--rev",
            revisionIdTwo));
  }

  @Override
  public long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    String hgTimeString = executeCommand(replaceTemplateValue(
            REVISION_AGE_COMMAND,
            REVISION_ID_TEMPLATE,
            revisionId));

    // hgdate is UTC timestamp + local offset,
    // e.g. 1440601290 -7200 (for France, which is UTC + 2H)
    // We only care about the UTC bit.
    return extractUnixTimestamp(hgTimeString);
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    String hgChangedFilesString = executeCommand(replaceTemplateValue(
        CHANGED_FILES_COMMAND,
        REVISION_ID_TEMPLATE,
        fromRevisionId));
    return FluentIterable.of(hgChangedFilesString.split("\0"))
        .filter(input -> !Strings.isNullOrEmpty(input))
        .toSet();
  }

  @Override
  public ImmutableMap<String, String> bookmarksRevisionsId(ImmutableSet<String> bookmarks)
      throws InterruptedException, VersionControlCommandFailedException {
    Path remoteNames = Paths.get(
        executeCommand(HG_ROOT_COMMAND),
        HG_ROOT_PATH,
        REMOTE_NAMES_FILENAME);

    ImmutableMap.Builder<String, String> bookmarksRevisions = ImmutableMap.builder();
    try {
      List<String> lines = Files.readAllLines(remoteNames);
      lines.forEach(line -> {
        for (String bookmark : bookmarks) {
          if (line.endsWith(bookmark)) {
            String[] parts = line.split(" ");
            bookmarksRevisions.put(parts[2], parts[0]);
          }
        }
      });
    } catch (IOException e) {
      return ImmutableMap.of();
    }
    return bookmarksRevisions.build();
  }

  private String executeCommand(Iterable<String> command)
      throws VersionControlCommandFailedException, InterruptedException {
    command = replaceTemplateValue(command, HG_CMD_TEMPLATE, hgCmd);
    String commandString = commandAsString(command);
    LOG.debug("Executing command: " + commandString);

    ProcessExecutorParams processExecutorParams = ProcessExecutorParams.builder()
        .setCommand(command)
        .setDirectory(projectRoot)
        .setEnvironment(environment)
        .build();

    ProcessExecutor.Result result;
    try (
        PrintStream stdout = new PrintStream(new ByteArrayOutputStream());
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
          "Received no output from launched process for command: " + commandString
      );
    }

    if (result.getExitCode() != 0) {
      throw new VersionControlCommandFailedException(
          result.getMessageForUnexpectedResult(commandString));
    }

    return cleanResultString(resultString.get());
  }


  private static String validateRevisionId(String revisionId)
      throws VersionControlCommandFailedException {
    Matcher revisionIdMatcher = HG_REVISION_ID_PATTERN.matcher(revisionId);
    if (!revisionIdMatcher.matches()) {
      throw new VersionControlCommandFailedException(revisionId + " is not a valid revision ID.");
    }
    return revisionId;
  }

  private static long extractUnixTimestamp(String hgTimestampString)
      throws VersionControlCommandFailedException {
    Matcher tsMatcher = HG_DATE_PATTERN.matcher(hgTimestampString);

    if (!tsMatcher.matches()) {
      throw new VersionControlCommandFailedException(
          hgTimestampString + " is not a valid Mercurial timestamp.");
    }

    return Long.valueOf(tsMatcher.group(HG_UNIX_TS_GROUP_INDEX));
  }

  private static Iterable<String> replaceTemplateValue(
      Iterable<String> values, final String template, final String replacement) {
    return StreamSupport.stream(values.spliterator(), false)
        .map(text -> text.contains(template) ? text.replace(template, replacement) : text)
        .collect(MoreCollectors.toImmutableList());
  }

  private static String commandAsString(Iterable<String> command) {
    return Joiner.on(" ").join(command);
  }

  private static String cleanResultString(String result) {
    return result.trim().replace("\'", "").replace("\n", "");
  }
}
