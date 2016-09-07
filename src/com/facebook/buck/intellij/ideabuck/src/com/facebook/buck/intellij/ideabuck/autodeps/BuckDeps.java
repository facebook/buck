/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckDeps {
  private static final Logger LOG = Logger.getInstance(BuckDeps.class);
  private static final Pattern depsStartPattern = Pattern.compile("(?s)(.*)deps[^\\[]=[^\\[]\\[");
  private static final Pattern visibilityStartPattern =
      Pattern.compile("(?s)(.*)visibility[^\\[]=[^\\[]\\[");

  private BuckDeps() {}

  public static void addDeps(
      Project project,
      String importBuckPath,
      String currentBuckPath,
      String importTarget,
      String currentTarget) {
    String absoluteCurrentBuckPath = project.getBaseDir().getPath() + "/" + currentBuckPath;

    StringBuilder importBuck = new StringBuilder(readBuckFile(absoluteCurrentBuckPath + "/BUCK"));
    String newDep = "'//" + importBuckPath + ":" + importTarget + "'";
    int startOfNewTarget = importBuck.indexOf("'" + currentTarget + "'");
    int startOfDeps = 0;
    int endOfDeps;

    Matcher startMatcher = depsStartPattern.matcher(importBuck);
    if (startMatcher.find(startOfNewTarget)) {
      startOfDeps = startMatcher.end();
    }

    endOfDeps = importBuck.indexOf("]", startOfDeps);

    if (!importBuck.substring(startOfDeps, endOfDeps).contains(newDep)) {
      importBuck.insert(
          startOfDeps,
          "\n\t\t" + newDep + ",");
      writeBuckFile(absoluteCurrentBuckPath + "/BUCK", importBuck.toString());
    }
    addVisibility(project, importBuckPath, currentBuckPath, importTarget, currentTarget);
  }

  public static void addVisibility(
      Project project,
      String importBuckPath,
      String currentBuckPath,
      String importTarget,
      String currentTarget) {
    String absoluteImportBuckPath = project.getBaseDir().getPath() + "/" + importBuckPath;

    StringBuilder currentBuck = new StringBuilder(readBuckFile(absoluteImportBuckPath + "/BUCK"));
    String newVisibility = "'//" + currentBuckPath + ":" + currentTarget + "'";
    int startOfNewTarget = currentBuck.indexOf("'" + importTarget + "'");
    int startOfVisibility = 0;
    int endOfVisibility;

    Matcher startMatcher = visibilityStartPattern.matcher(currentBuck);
    if (startMatcher.find(startOfNewTarget)) {
      startOfVisibility = startMatcher.end();
    }

    endOfVisibility = currentBuck.indexOf("]", startOfVisibility);

    if (!currentBuck.substring(startOfVisibility, endOfVisibility).contains(newVisibility) &&
        !currentBuck.substring(startOfVisibility, endOfVisibility).contains("'PUBLIC'")) {
      currentBuck.insert(
          startOfVisibility,
          "\n\t\t" + newVisibility + ",");
      writeBuckFile(absoluteImportBuckPath + "/BUCK", currentBuck.toString());
    }
  }

  private static String readBuckFile (String buckFilePath) {
    String str = "";
    File file = new File(buckFilePath);
    try (FileInputStream fis = new FileInputStream(file)) {

      byte[] data = new byte[(int) file.length()];
      fis.read(data);
      fis.close();
      str = new String(data, "UTF-8");
    } catch (FileNotFoundException e) {
      LOG.error(e.toString());
    } catch (UnsupportedEncodingException e) {
      LOG.error(e.toString());
    } catch (IOException e) {
      LOG.error(e.toString());
    }
    return str;
  }

  private static void writeBuckFile (String buckFilePath, String text) {

    try (PrintWriter out = new PrintWriter(buckFilePath)) {
      out.write(text);
    } catch (FileNotFoundException e) {
      LOG.error(e.toString());
    } catch (IOException e) {
      LOG.error(e.toString());
    }
  }
}
