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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.Genrule;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ApkGenruleDescriptionTest {

  @Test
  public void testClasspathTransitiveDepsBecomeFirstOrderDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    InstallableApk installableApk =
        ruleResolver.addToIndex(
            new FakeInstallable(BuildTargetFactory.newInstance("//:installable"), pathResolver));
    BuildRule transitiveDep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver);
    BuildRule dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDep.getBuildTarget())
            .build(ruleResolver);
    Genrule genrule =
        (Genrule) ApkGenruleBuilder.create(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd("$(classpath //exciting:target)")
            .setApk(installableApk.getBuildTarget())
            .build(ruleResolver);
    assertThat(genrule.getDeps(), Matchers.hasItems(dep, transitiveDep));
  }

  private static class FakeInstallable extends FakeBuildRule implements InstallableApk {

    public FakeInstallable(
        BuildTarget buildTarget,
        SourcePathResolver resolver) {
      super(buildTarget, resolver);
    }

    @Override
    public Path getManifestPath() {
      return Paths.get("nothing");
    }

    @Override
    public Path getApkPath() {
      return Paths.get("buck-out/app.apk");
    }

    @Override
    public Optional<ExopackageInfo> getExopackageInfo() {
      return Optional.absent();
    }

  }

}
