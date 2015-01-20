/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedSetMultimap;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

public class MergeAndroidResourcesStepTest {
  @Test
  public void testGenerateRDotJavaForMultipleSymbolsFiles() throws IOException {

    RDotTxtEntryBuilder entriesBuilder = new RDotTxtEntryBuilder();

    // Merge everything into the same package space.
    String sharedPackageName = "com.facebook.abc";
    entriesBuilder.add(new RDotTxtFile(sharedPackageName, "a-R.txt",
        ImmutableList.of(
            "int id a1 0x7f010001",
            "int id a2 0x7f010002",
            "int string a1 0x7f020001")));

    entriesBuilder.add(new RDotTxtFile(sharedPackageName, "b-R.txt",
        ImmutableList.of(
            "int id b1 0x7f010001",
            "int id b2 0x7f010002")));

    entriesBuilder.add(new RDotTxtFile(sharedPackageName, "c-R.txt",
        ImmutableList.of(
            "int attr c1 0x7f010001",
            "int[] styleable c1 { 0x7f010001 }")));

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(entriesBuilder.getProjectFilesystem())
        .build();

    SortedSetMultimap<String, RDotTxtEntry> packageNameToResources =
        MergeAndroidResourcesStep.sortSymbols(
            entriesBuilder.buildFilePathToPackageNameSet(),
            Optional.<ImmutableMap<RDotTxtEntry, String>>absent(),
            /* warnMissingResource */ false,
            executionContext);

    assertEquals(1, packageNameToResources.keySet().size());
    SortedSet<RDotTxtEntry> resources = packageNameToResources.get(sharedPackageName);
    assertEquals(7, resources.size());

    Set<String> uniqueEntries = Sets.newHashSet();
    for (RDotTxtEntry resource : resources) {
      if (!resource.type.equals(RDotTxtEntry.RType.STYLEABLE)) {
        assertFalse("Duplicate ids should be fixed by renumerate=true; duplicate was: " +
            resource.idValue, uniqueEntries.contains(resource.idValue));
        uniqueEntries.add(resource.idValue);
      }
    }

    assertEquals(6, uniqueEntries.size());

    // All good, no need to further test whether we can write the Java file correctly...
  }

  @Test
  public void testGenerateRDotJavaForOneSymbolsFile() throws IOException {
    String symbolsFile = BuckConstant.GEN_DIR +
        "/android_res/com/facebook/http/__res_text_symbols__/R.txt";
    String rDotJavaPackage = "com.facebook";
    final ImmutableList<String> outputTextSymbols = ImmutableList.<String>builder()
        .add("int id placeholder 0x7f020000")
        .add("int string debug_http_proxy_dialog_title 0x7f030004")
        .add("int string debug_http_proxy_hint 0x7f030005")
        .add("int string debug_http_proxy_summary 0x7f030003")
        .add("int string debug_http_proxy_title 0x7f030002")
        .add("int string debug_ssl_cert_check_summary 0x7f030001")
        .add("int string debug_ssl_cert_check_title 0x7f030000")
        .add("int styleable SherlockMenuItem_android_visible 4")
        .add("int[] styleable SherlockMenuView { 0x7f010026, 0x7f010027, 0x7f010028, 0x7f010029, " +
            "0x7f01002a, 0x7f01002b, 0x7f01002c, 0x7f01002d }")
        .build();
    RDotTxtEntryBuilder entriesBuilder = new RDotTxtEntryBuilder();
    entriesBuilder.add(new RDotTxtFile(rDotJavaPackage, symbolsFile, outputTextSymbols));

    FakeProjectFilesystem filesystem = entriesBuilder.getProjectFilesystem();

    Path uberRDotTxt = Paths.get("R.txt");
    filesystem.writeLinesToPath(outputTextSymbols, uberRDotTxt);

    HasAndroidResourceDeps resource = AndroidResourceRuleBuilder.newBuilder()
        .setResolver(new SourcePathResolver(new BuildRuleResolver()))
        .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/facebook/http:res"))
        .setRes(Paths.get("res"))
        .setRDotJavaPackage("com.facebook")
        .build();

    MergeAndroidResourcesStep mergeStep = new MergeAndroidResourcesStep(
        ImmutableList.of(resource),
        Optional.of(uberRDotTxt),
        /* warnMissingResource */ false,
        Paths.get("output"));

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(filesystem)
        .build();

    assertEquals(0, mergeStep.execute(executionContext));

    // Verify that the correct Java code is generated.
    assertEquals(
        "package com.facebook;\n" +
        "\n" +
        "public class R {\n" +
        "\n" +
        "  public static class id {\n" +
        "    public static final int placeholder=0x7f020000;\n" +
        "  }\n" +
        "\n" +
        "  public static class string {\n" +
        "    public static final int debug_http_proxy_dialog_title=0x7f030004;\n" +
        "    public static final int debug_http_proxy_hint=0x7f030005;\n" +
        "    public static final int debug_http_proxy_summary=0x7f030003;\n" +
        "    public static final int debug_http_proxy_title=0x7f030002;\n" +
        "    public static final int debug_ssl_cert_check_summary=0x7f030001;\n" +
        "    public static final int debug_ssl_cert_check_title=0x7f030000;\n" +
        "  }\n" +
        "\n" +
        "  public static class styleable {\n" +
        "    public static final int SherlockMenuItem_android_visible=4;\n" +
        "    public static final int[] SherlockMenuView={ 0x7f010026, 0x7f010027, 0x7f010028, " +
                "0x7f010029, 0x7f01002a, 0x7f01002b, 0x7f01002c, 0x7f01002d };\n" +
        "  }\n" +
        "\n" +
        "}\n",
        filesystem.readFileIfItExists(Paths.get("output/com/facebook/R.java")).get());
  }

  // sortSymbols has a goofy API.  This will help.
  private static class RDotTxtEntryBuilder {
    private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    private final ImmutableMap.Builder<Path, String> filePathToPackageName =
        ImmutableMap.builder();

    public RDotTxtEntryBuilder() {
    }

    public void add(RDotTxtFile entry) throws IOException {
      filesystem.writeLinesToPath(entry.contents, entry.filePath);
      filePathToPackageName.put(entry.filePath, entry.packageName);
    }

    public Map<Path, String> buildFilePathToPackageNameSet() {
      return filePathToPackageName.build();
    }

    public FakeProjectFilesystem getProjectFilesystem() {
      return filesystem;
    }
  }

  private static class RDotTxtFile {
    public String packageName;
    public Path filePath;
    public ImmutableList<String> contents;

    public RDotTxtFile(String packageName, String filePath, ImmutableList<String> contents) {
      this.packageName = packageName;
      this.filePath = Paths.get(filePath);
      this.contents = contents;
    }
  }
}
