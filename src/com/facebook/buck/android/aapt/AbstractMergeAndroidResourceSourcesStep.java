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

import com.android.ide.common.res2.MergedResourceWriter;
import com.android.ide.common.res2.MergingException;
import com.android.ide.common.res2.NoOpResourcePreprocessor;
import com.android.ide.common.res2.ResourceMerger;
import com.android.ide.common.res2.ResourceSet;
import com.android.utils.ILogger;
import com.facebook.buck.android.BuckEventAndroidLogger;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Merges multiple directories containing Android resource sources into one directory. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractMergeAndroidResourceSourcesStep implements Step {

  private static final Logger LOG = Logger.get(AbstractMergeAndroidResourceSourcesStep.class);

  @Value.Parameter
  protected abstract ImmutableList<Path> getResPaths();

  @Value.Parameter
  protected abstract Path getOutFolderPath();

  @Value.Parameter
  protected abstract Path getTmpFolderPath();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getOutFolderPath().isAbsolute(),
        "Android merge out folder path must be absolute but was %s",
        getOutFolderPath());
    Preconditions.checkArgument(
        getTmpFolderPath().isAbsolute(),
        "Android merge tmp folder path must be absolute but was %s",
        getOutFolderPath());
    for (Path resPath : getResPaths()) {
      Preconditions.checkArgument(
          resPath.isAbsolute(), "Android merge resource path must be absolute but was %s", resPath);
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ResourceMerger merger = new ResourceMerger(1);
    try {
      for (Path resPath : getResPaths()) {
        ResourceSet set = new ResourceSet(resPath.toString(), true);
        set.setDontNormalizeQualifiers(true);
        set.addSource(resPath.toFile());
        set.loadFromFiles(new ResourcesSetLoadLogger(context.getBuckEventBus()));
        merger.addDataSet(set);
      }
      MergedResourceWriter writer =
          MergedResourceWriter.createWriterWithoutPngCruncher(
              getOutFolderPath().toFile(),
              null /*publicFile*/,
              null /*blameLogFolder*/,
              new NoOpResourcePreprocessor(),
              getTmpFolderPath().toFile());
      merger.mergeData(writer, /* cleanUp */ false);
    } catch (MergingException e) {
      LOG.error(e, "Failed merging resources.");
      return StepExecutionResults.ERROR;
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "merge-resources";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder sb = new StringBuilder(getShortName());
    sb.append(' ');
    Joiner.on(',').appendTo(sb, getResPaths());
    sb.append(" -> ");
    sb.append(getOutFolderPath());
    return sb.toString();
  }

  private static class ResourcesSetLoadLogger extends BuckEventAndroidLogger implements ILogger {
    public ResourcesSetLoadLogger(BuckEventBus eventBus) {
      super(eventBus);
    }
  }
}
