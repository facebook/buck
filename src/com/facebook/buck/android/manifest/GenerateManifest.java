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

package com.facebook.buck.android.manifest;

import com.android.common.utils.ILogger;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenerateManifest {

  public static String generateXml(
      Path skeletonManifestPath,
      String moduleName,
      ImmutableList<Path> libraryManifestPaths,
      ImmutableMap<String, String> placeholders,
      Path outManifestPath,
      Path mergeReportPath,
      ILogger logger)
      throws IOException {
    if (skeletonManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Skeleton manifest filepath is missing");
    }

    if (outManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Output Manifest filepath is missing");
    }

    Files.createParentDirs(outManifestPath.toFile());

    List<File> libraryManifestFiles =
        libraryManifestPaths.stream().map(Path::toFile).collect(ImmutableList.toImmutableList());

    MergingReport mergingReport =
        mergeManifests(
            moduleName,
            skeletonManifestPath.toFile(),
            libraryManifestFiles,
            mergeReportPath,
            logger);

    String xmlText = mergingReport.getMergedDocument(MergingReport.MergedManifestKind.MERGED);
    xmlText = replacePlaceholders(xmlText, placeholders);

    if (Platform.detect() == Platform.WINDOWS) {
      // Convert line endings to Lf on Windows.
      xmlText = xmlText.replace("\r\n", "\n");
    }

    return xmlText;
  }

  private static MergingReport mergeManifests(
      String moduleName,
      File mainManifestFile,
      List<File> libraryManifestFiles,
      Path mergeReportPath,
      ILogger logger) {
    try {
      ManifestMerger2.Invoker<?> manifestInvoker =
          ManifestMerger2.newMerger(
              mainManifestFile, logger, ManifestMerger2.MergeType.APPLICATION);
      if (!APKModule.isRootModule(moduleName)) {
        manifestInvoker.setPlaceHolderValue("split", moduleName);
      } else {
        manifestInvoker.withFeatures(ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT);
      }

      MergingReport mergingReport =
          manifestInvoker
              .withFeatures(
                  ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS,
                  ManifestMerger2.Invoker.Feature.SKIP_BLAME)
              .addLibraryManifests(Iterables.toArray(libraryManifestFiles, File.class))
              .setMergeReportFile(mergeReportPath.toFile())
              .merge();
      if (mergingReport.getResult().isError()) {
        for (MergingReport.Record record : mergingReport.getLoggingRecords()) {
          logger.error(null, record.toString());
        }
        throw new HumanReadableException("Error generating manifest file");
      }

      return mergingReport;
    } catch (ManifestMerger2.MergeFailureException e) {
      throw new HumanReadableException(
          e.getCause(), "Error generating manifest file: %s", e.getMessage());
    }
  }

  @VisibleForTesting
  static String replacePlaceholders(String content, ImmutableMap<String, String> placeholders) {
    Iterable<String> escaped = Iterables.transform(placeholders.keySet(), Pattern::quote);

    Joiner joiner = Joiner.on("|");
    String patternString =
        Pattern.quote("${") + "(" + joiner.join(escaped) + ")" + Pattern.quote("}");
    Pattern pattern = Pattern.compile(patternString);
    Matcher matcher = pattern.matcher(content);

    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(sb, placeholders.get(matcher.group(1)));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
