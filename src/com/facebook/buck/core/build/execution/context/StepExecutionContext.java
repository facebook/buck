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

package com.facebook.buck.core.build.execution.context;

import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.RuleKeyDiagnosticsMode;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.worker.DefaultWorkerProcess;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.io.Closer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.immutables.value.Value;

/** The context exposed for executing {@link com.facebook.buck.step.Step}s */
@BuckStyleValueWithBuilder
@SuppressWarnings(
    "immutables:from") // Suppress warning for event bus being different type in superclass
public abstract class StepExecutionContext extends IsolatedExecutionContext {

  /**
   * Creates {@link StepExecutionContext} from {@link ExecutionContext}, {@code ruleCellRoot} and
   * {@code actionId}
   */
  public static StepExecutionContext from(
      ExecutionContext executionContext, AbsPath ruleCellRoot, String actionId) {
    return StepExecutionContext.builder()
        .setConsole(executionContext.getConsole())
        .setBuckEventBus(executionContext.getBuckEventBus())
        .setPlatform(executionContext.getPlatform())
        .setEnvironment(executionContext.getEnvironment())
        .setProcessExecutor(executionContext.getProcessExecutor())
        .setAndroidDevicesHelper(executionContext.getAndroidDevicesHelper())
        .setPersistentWorkerPools(executionContext.getPersistentWorkerPools())
        .setBuildCellRootPath(executionContext.getBuildCellRootPath())
        .setProjectFilesystemFactory(executionContext.getProjectFilesystemFactory())
        .setClassLoaderCache(executionContext.getClassLoaderCache())
        .setShouldReportAbsolutePaths(executionContext.shouldReportAbsolutePaths())
        .setRuleKeyDiagnosticsMode(executionContext.getRuleKeyDiagnosticsMode())
        .setTruncateFailingCommandEnabled(executionContext.isTruncateFailingCommandEnabled())
        .setWorkerProcessPools(executionContext.getWorkerProcessPools())
        .setRuleCellRoot(ruleCellRoot)
        .setActionId(actionId)
        .setClock(executionContext.getClock())
        .setWorkerToolPools(executionContext.getWorkerToolPools())
        .build();
  }

  public abstract BuckEventBus getBuckEventBus();

  public abstract Optional<AndroidDevicesHelper> getAndroidDevicesHelper();

  /**
   * Worker process pools that are persisted across buck invocations inside buck daemon. If buck is
   * running without daemon, there will be no persisted pools.
   */
  public abstract Optional<ConcurrentMap<String, WorkerProcessPool<DefaultWorkerProcess>>>
      getPersistentWorkerPools();

  /**
   * The absolute path to the cell where this build was invoked.
   *
   * <p>For example, consider two cells: cell1 and cell2. If a build like "buck build cell2//:bar"
   * was invoked from cell1, this method would return cell1's path.
   *
   * <p>See {@link com.facebook.buck.core.build.context.BuildContext#getBuildCellRootPath}.
   */
  public abstract Path getBuildCellRootPath();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  @Value.Default
  public boolean shouldReportAbsolutePaths() {
    return false;
  }

  @Value.Default
  public RuleKeyDiagnosticsMode getRuleKeyDiagnosticsMode() {
    return RuleKeyDiagnosticsMode.NEVER;
  }

  @Value.Default
  public boolean isTruncateFailingCommandEnabled() {
    return true;
  }

  /**
   * Worker process pools that you can populate as needed. These will be destroyed as soon as buck
   * invocation finishes, thus, these pools are not persisted across buck invocations.
   */
  @Value.Default
  public ConcurrentMap<String, WorkerProcessPool<DefaultWorkerProcess>> getWorkerProcessPools() {
    return new ConcurrentHashMap<>();
  }

  @Override
  @Value.Default
  public IsolatedEventBus getIsolatedEventBus() {
    return getBuckEventBus().isolated();
  }

  @Override
  protected void registerCloseables(Closer closer) {
    super.registerCloseables(closer);
    getAndroidDevicesHelper().ifPresent(closer::register);
    // The closer closes in reverse order, so do the clear first.
    closer.register(getWorkerProcessPools()::clear);
    getWorkerProcessPools().values().forEach(closer::register);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableStepExecutionContext.Builder {}
}
