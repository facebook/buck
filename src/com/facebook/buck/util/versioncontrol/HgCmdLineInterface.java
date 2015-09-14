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
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HgCmdLineInterface implements VersionControlCmdLineInterface {
  private static final Logger LOG = Logger.get(VersionControlCmdLineInterface.class);

  private static final Pattern HG_REVISION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");
  private static final Pattern HG_DATE_PATTERN = Pattern.compile("(\\d+)\\s([\\-\\+]?\\d+)");
  private static final int HG_UNIX_TS_GROUP_INDEX = 1;

  private static final String HG_CMD_TEMPLATE = "{hg}";
  private static final String NO_CHANGES_STATUS = "";
  private static final String NAME_TEMPLATE = "{name}";
  private static final String REVISION_ID_TEMPLATE = "{revision}";
  private static final String REVISION_IDS_TEMPLATE = "{revisions}";

  private static final ImmutableList<String> CURRENT_REVISION_ID_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "id", "-i");

  private static final ImmutableList<String> REVISION_ID_FOR_NAME_COMMAND_TEMPLATE =
      ImmutableList.of(HG_CMD_TEMPLATE, "id", "-i", "-r", NAME_TEMPLATE);

  private static final ImmutableList<String> STATUS_COMMAND =
      ImmutableList.of(HG_CMD_TEMPLATE, "status");

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

  private final ProcessExecutor processExecutor;
  private final File projectRoot;
  private final String hgCmd;

  public HgCmdLineInterface(ProcessExecutor processExecutor, File projectRoot, String hgCmd) {
    this.processExecutor = processExecutor;
    this.projectRoot = projectRoot;
    this.hgCmd = hgCmd;
  }

  @Override
  public boolean isSupportedVersionControlSystem() {
    return true; // Mercurial is supported
  }

  @Override
  public boolean hasWorkingDirectoryChanges()
      throws VersionControlCommandFailedException, InterruptedException {
    return !executeCommand(STATUS_COMMAND).equals(NO_CHANGES_STATUS);
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

  private String executeCommand(Iterable<String> command)
      throws VersionControlCommandFailedException, InterruptedException {
    command = replaceTemplateValue(command, HG_CMD_TEMPLATE, hgCmd);
    ProcessExecutorParams processExecutorParams = ProcessExecutorParams.builder()
        .setCommand(command)
        .setDirectory(projectRoot)
        .build();

    String commandString = commandAsString(command);
    LOG.debug("Executing command: " + commandString);

    try {
      Optional<String> resultString =
          processExecutor.launchAndExecute(
              processExecutorParams,
              // Must specify that stdout is expected or else output may be wrapped
              // in Ansi escape chars.
              ImmutableSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>absent(),
              /* timeOutHandler */ Optional.<Function<Process, Void>>absent()
              ).getStdout();

      if (!resultString.isPresent()) {
        throw new VersionControlCommandFailedException(
            "Received no output from launched process for command: " +
                commandString);
      }

      return cleanResultString(resultString.get());
    } catch (IOException e) {
      throw new VersionControlCommandFailedException(e);
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
    return FluentIterable
        .from(values)
        .transform(
            new Function<String, String>() {
              @Override
              public String apply(String text) {
                return text.contains(template) ? text.replace(template, replacement) : text;
              }
            })
        .toList();
  }

  private static String commandAsString(Iterable<String> command) {
    return Joiner.on(" ").join(command);
  }

  private static String cleanResultString(String result) {
    return result.trim().replace("\'", "").replace("\n", "");
  }
}
