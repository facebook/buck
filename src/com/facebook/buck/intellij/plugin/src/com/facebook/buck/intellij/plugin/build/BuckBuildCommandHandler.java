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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.OpenSourceUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckBuildCommandHandler extends BuckCommandHandler {

  private static final String[] IGNORED_OUTPUT_LINES = new String[]{
      "Using buckd",
      "Using watchman",
  };

  private static final String UNKNOWN_ERROR_MESSAGE = "Unknown error";
  private static final String ERROR_PREFIX_FOR_MESSAGE = "BUILD FAILED:";
  private static final Pattern[] ERROR_PATTERNS = new Pattern[]{
      Pattern.compile(ERROR_PREFIX_FOR_MESSAGE + ".*"),
      Pattern.compile("FAIL.*"),
      Pattern.compile("Errors:.*"),
      Pattern.compile("No devices found.*"),
      Pattern.compile("NameError.*"),
      Pattern.compile(".*is not a valid option\\s*$"),
  };

  private static final String[] SUCCESS_PREFIXES = new String[]{
      "OK ",
      "Successfully",
      "[-] BUILDING...FINISHED",
      "[-] INSTALLING...FINISHED",
  };

  private static final Pattern SOURCE_FILE_PATTERN =
      Pattern.compile("^([\\s\\S]*\\.(cpp|java|hpp|cxx|cc|c|py)):([0-9]+)([\\s\\S]*)\\s*$");

  private static final Pattern BUILD_PROGRESS_PATTERN =
      Pattern.compile("^BUILT //[\\s\\S]*\\(([0-9]+)/([0-9]+) JOBS\\)\\s*$");

  private static final ConsoleViewContentType GRAY_OUTPUT =
      new ConsoleViewContentType(
          "BUCK_GRAY_OUTPUT", TextAttributesKey.createTextAttributesKey("CONSOLE_DARKGRAY_OUTPUT"));

  private static final ConsoleViewContentType GREEN_OUTPUT =
      new ConsoleViewContentType(
          "BUCK_GREEN_OUTPUT", TextAttributesKey.createTextAttributesKey("CONSOLE_GREEN_OUTPUT"));

  private boolean showFailedNotification = false;
  private String currentErrorMessage;

  public BuckBuildCommandHandler(
      final Project project,
      final VirtualFile root,
      final BuckCommand command) {
    super(project, VfsUtil.virtualToIoFile(root), command);
  }

  @Override
  protected void notifyLines(Key outputType, Iterator<String> lines, StringBuilder lineBuilder) {
    while (lines.hasNext()) {
      boolean failed = parseOutputLine(lines.next());
      if (!showFailedNotification) {
        showFailedNotification = failed;
      }
    }
  }

  @Override
  protected boolean beforeCommand() {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project());

    if (!buildManager.isBuckProject(project)) {
      BuckToolWindowFactory.outputConsoleMessage(
          project,
          BuckBuildManager.NOT_BUCK_PROJECT_ERROR_MESSAGE, ConsoleViewContentType.ERROR_OUTPUT);
      return false;
    }

    buildManager.setBuilding(project, true);
    BuckToolWindowFactory.cleanConsole(project());

    String headMessage = "Running '" + command().getCommandLineString() + "'\n";
    BuckToolWindowFactory.outputConsoleMessage(
        project,
        headMessage, GRAY_OUTPUT);
    return true;
  }

  @Override
  protected void afterCommand() {
    BuckBuildManager.getInstance(project()).setBuilding(project, false);

    // Popup notification if needed.
    if (showFailedNotification && !BuckToolWindowFactory.isToolWindowVisible(project)) {
      if (currentErrorMessage == null) {
        currentErrorMessage = UNKNOWN_ERROR_MESSAGE;
      } else {
        currentErrorMessage = currentErrorMessage.replaceAll(ERROR_PREFIX_FOR_MESSAGE, "");
      }
      BuckBuildNotification.createBuildFailedNotification(
          command.name(), currentErrorMessage).notify(project);
    }
  }

  /**
   * Parse a line of the buck command output for:
   * 1. Calculate the progress.
   * 2. Ignore unused lines, for example "Using buckd.".
   * 3. Print to console window with different colors.
   *
   * @return boolean failed or not
   */
  private boolean parseOutputLine(String line) {
    for (String ignored : IGNORED_OUTPUT_LINES) {
      if (line.startsWith(ignored)) {
        return false;
      }
    }

    // Extract the jobs information and calculate the progress.
    Matcher matcher = BUILD_PROGRESS_PATTERN.matcher(line);
    if (matcher.matches()) {
      double finishedJob = Double.parseDouble(matcher.group(1));
      double totalJob = Double.parseDouble(matcher.group(2));
      BuckBuildManager.getInstance(project()).setProgress(finishedJob / totalJob);
      return false;
    }

    // Red color.
    for (Pattern errorPattern : ERROR_PATTERNS) {
      if (errorPattern.matcher(line).lookingAt()) {
        BuckToolWindowFactory.outputConsoleMessage(
            project(), line, ConsoleViewContentType.ERROR_OUTPUT);
        if (currentErrorMessage == null && line.startsWith(ERROR_PREFIX_FOR_MESSAGE)) {
          currentErrorMessage = line;
        }
        return true;
      }
    }

    // Green color.
    for (String successPrefix : SUCCESS_PREFIXES) {
      if (line.startsWith(successPrefix)) {
        BuckToolWindowFactory.outputConsoleMessage(
            project(), line, GREEN_OUTPUT);
        return false;
      }
    }

    // Test if it is a java compile error message with a java file path.
    Matcher compilerErrorMatcher = SOURCE_FILE_PATTERN.matcher(line);
    if (compilerErrorMatcher.matches()) {
      final String sourceFile = compilerErrorMatcher.group(1);
      final String lineNumber = compilerErrorMatcher.group(3);
      final String errorMessage = compilerErrorMatcher.group(4);

      String relativePath = sourceFile.replaceAll(project.getBasePath(), "");
      final VirtualFile virtualFile = pathToVirtualFile(relativePath);
      if (virtualFile == null) {
        BuckToolWindowFactory.outputConsoleMessage(
            project(), line, ConsoleViewContentType.ERROR_OUTPUT);
      } else {
        BuckToolWindowFactory.outputConsoleHyperlink(project(), relativePath + ":" + lineNumber,
            new HyperlinkInfo() {
              @Override
              public void navigate(Project project) {
                int lineInteger;
                try {
                  lineInteger = Integer.parseInt(lineNumber) - 1;
                } catch (NumberFormatException e) {
                  lineInteger = 0;
                }
                OpenSourceUtil.navigate(true,
                    new OpenFileDescriptor(project, virtualFile, lineInteger, 0));
              }
            });
        BuckToolWindowFactory.outputConsoleMessage(
            project(), errorMessage, ConsoleViewContentType.ERROR_OUTPUT);
      }
    } else {
      if (!line.trim().isEmpty()) {
        BuckToolWindowFactory.outputConsoleMessage(
            project(), line, ConsoleViewContentType.NORMAL_OUTPUT);
      }
    }
    return false;
  }

  @Nullable
  private VirtualFile pathToVirtualFile(String relativePath) {
    VirtualFile projectPath = project.getBaseDir();
    return projectPath.findFileByRelativePath(relativePath);
  }
}
