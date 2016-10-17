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

package com.facebook.buck.rage;

import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Interface around the 'backend' of submitting a defect report.
 */
public interface DefectReporter {
  DefectSubmitResult submitReport(DefectReport defectReport) throws IOException;

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractDefectSubmitResult {
    String getReportSubmitLocation();
    Optional<String> getReportSubmitMessage();
    Optional<String> getReportSubmitErrorMessage();
    Optional<Boolean> getUploadSuccess();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractDefectReport {
    Optional<UserReport> getUserReport();
    ImmutableSet<BuildId> getHighlightedBuildIds();
    ImmutableSet<Path> getIncludedPaths();
    BuildEnvironmentDescription getBuildEnvironmentDescription();
    Optional<SourceControlInfo> getSourceControlInfo();
    Optional<String> getExtraInfo();
    UserLocalConfiguration getUserLocalConfiguration();
  }
}
