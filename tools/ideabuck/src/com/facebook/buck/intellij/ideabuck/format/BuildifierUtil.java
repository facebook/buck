/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.io.IOException;
import java.util.Optional;

/** Utility methods to invoke {@code buildifier} on a target file or document. */
public class BuildifierUtil {

  private static final Logger LOGGER = Logger.getInstance(BuildifierUtil.class);

  /**
   * Reformats the given {@link PsiFile} using {@code buildifier}, returning true if reformatting
   * changed the file.
   */
  public static boolean doReformat(PsiFile psiFile) {
    Project project = psiFile.getProject();
    return Optional.ofNullable(PsiDocumentManager.getInstance(project).getDocument(psiFile))
        .map(document -> doReformat(project, document))
        .orElse(false);
  }

  /**
   * Reformats the given {@link VirtualFile} using {@code buildifier}, returning true if
   * reformatting changed the file.
   */
  public static boolean doReformat(Project project, VirtualFile file) {
    return Optional.ofNullable(FileDocumentManager.getInstance().getDocument(file))
        .map(document -> doReformat(project, document))
        .orElse(false);
  }

  /**
   * Reformats the given {@link Document} using {@code buildifier}, returning true if reformatting
   * changed the document.
   */
  public static boolean doReformat(Project project, Document document) {
    if (!document.isWritable()) {
      LOGGER.warn("Could not reformat because Document is not writable");
      return false;
    }
    Optional<String> formattedText = reformatText(project, document.getText());
    formattedText.ifPresent(text -> WriteAction.run(() -> document.setText(text)));
    return formattedText.isPresent();
  }

  /**
   * Reformats the given {@link String} using the {@link Project}'s preferred {@code buildifier}.
   *
   * <p>Note: this method only returns a non-empty {@link Optional} if reformatting results in a
   * change to the text.
   */
  public static Optional<String> reformatText(Project project, String text) {
    BuckExecutableSettingsProvider executableSettings =
        BuckExecutableSettingsProvider.getInstance(project);
    String executable = executableSettings.resolveBuildifierExecutable();
    if (executable == null) {
      LOGGER.info("Could not reformat because no buildifier executable");
      return Optional.empty();
    }
    return reformatText(executable, text);
  }

  /**
   * Reformats the given {@link String} using the given {@code buildifier} executable.
   *
   * <p>Note: this method only returns a non-empty {@link Optional} if reformatting results in a
   * change to the text.
   */
  public static Optional<String> reformatText(String buildifierExecutable, String text) {
    try {
      GeneralCommandLine commandLine =
          new GeneralCommandLine()
              .withExePath(buildifierExecutable)
              .withParameters("-buildifier_disable=label", "-mode=print_if_changed")
              .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
      CapturingProcessHandler capturingProcessHandler = new CapturingProcessHandler(commandLine);
      byte[] textBytes = text.getBytes(CharsetToolkit.UTF8_CHARSET);
      capturingProcessHandler.getProcessInput().write(textBytes);
      capturingProcessHandler.getProcessInput().flush();
      capturingProcessHandler.getProcessInput().close();
      ProcessOutput processOutput = capturingProcessHandler.runProcess();
      if (!processOutput.isExitCodeSet() || processOutput.getExitCode() != 0) {
        LOGGER.debug(
            "Reformatting with buildifier failed [exit code="
                + processOutput.getExitCode()
                + "] stderr: "
                + processOutput.getStderr());
        return Optional.empty(); // failed to format text
      }
      String stdout = processOutput.getStdout();
      if ("".equals(stdout)) {
        LOGGER.debug("No change to original text");
        return Optional.empty(); // no change to original text
      }
      return Optional.of(stdout);
    } catch (IOException | ExecutionException e) {
      LOGGER.warn("Failed to reformat text using buildifier [" + buildifierExecutable + "]", e);
      return Optional.empty();
    }
  }
}
