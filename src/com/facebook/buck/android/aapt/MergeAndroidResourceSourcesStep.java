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

package com.facebook.buck.android.aapt;

import com.android.ide.common.internal.PngCruncher;
import com.android.ide.common.internal.PngException;
import com.android.ide.common.res2.MergedResourceWriter;
import com.android.ide.common.res2.MergingException;
import com.android.ide.common.res2.ResourceMerger;
import com.android.ide.common.res2.ResourceSet;
import com.android.utils.ILogger;
import com.facebook.buck.android.BuckEventAndroidLogger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Merges multiple directories containing Android resource sources into one directory.
 */
public class MergeAndroidResourceSourcesStep implements Step {

  private static final Logger LOG = Logger.get(MergeAndroidResourceSourcesStep.class);

  private final ImmutableList<Path> resPaths;
  private final Path outFolderPath;

  public MergeAndroidResourceSourcesStep(ImmutableList<Path> resPaths, Path outFolderPath) {
    this.resPaths = resPaths;
    this.outFolderPath = outFolderPath;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    ResourceMerger merger = new ResourceMerger();
    try {
      for (Path resPath : resPaths) {
        ResourceSet set = new ResourceSet(resPath.toString());
        set.setNormalizeResources(false);
        set.addSource(context.getProjectFilesystem().resolve(resPath).toFile());
        set.loadFromFiles(new ResourcesSetLoadLogger(context.getBuckEventBus()));
        merger.addDataSet(set);
      }
      MergedResourceWriter writer = new MergedResourceWriter(
          context.getProjectFilesystem().resolve(outFolderPath).toFile(),
          new NoopPngCruncher(),
          /* crunchPng */ false,
          /* crunch9Patch */ false);
      writer.setInsertSourceMarkers(false);
      merger.mergeData(writer, /* cleanUp */ false);
    } catch (MergingException e) {
      LOG.error(e, "Failed merging resources.");
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "merge-resources";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder sb = new StringBuilder(getShortName());
    sb.append(' ');
    Joiner.on(',').appendTo(sb, resPaths);
    sb.append(" -> ");
    sb.append(outFolderPath.toString());
    return sb.toString();
  }

  /**
   * The {@link MergedResourceWriter} shouldn't call any of these methods if png crunching
   * is disabled.
   */
  private static class NoopPngCruncher implements PngCruncher {

    @Override
    public int start() {
      return 0;
    }

    @Override
    public void crunchPng(int key, File from, File to) throws PngException {
      throw new RuntimeException("not implemented");
    }

    @Override
    public void end(int key) throws InterruptedException {
      // NOOP
    }
  }

  private static class ResourcesSetLoadLogger extends BuckEventAndroidLogger implements ILogger {
    public ResourcesSetLoadLogger(BuckEventBus eventBus) {
      super(eventBus);
    }
  }
}
