// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.bisect;

import com.android.tools.r8.bisect.BisectOptions.Result;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.NamingLens;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class BisectState {

  private static class Range {
    final int start;
    final int end;

    public Range(int start, int end) {
      this.start = start;
      this.end = end;
      assert verify();
    }

    public Range(String range) {
      int sep = range.indexOf(' ');
      start = Integer.parseInt(range.substring(0, sep).trim());
      end = Integer.parseInt(range.substring(sep + 1).trim());
      assert verify();
    }

    public void write(Writer writer) throws IOException {
      writer.write("" + start);
      writer.write(" ");
      writer.write("" + end);
    }

    public boolean isEmpty() {
      return start == end;
    }

    public int size() {
      return end - start;
    }

    public Range add(Range other) {
      if (isEmpty()) {
        return other;
      }
      if (other.isEmpty()) {
        return this;
      }
      assert start == other.end || end == other.start;
      return new Range(Integer.min(start, other.start), Integer.max(end, other.end));
    }

    public Range sub(Range other) {
      if (other.isEmpty()) {
        return this;
      }
      assert start <= other.start && other.end <= end;
      if (start == other.start) {
        return new Range(other.end, end);
      }
      assert end == other.end;
      return new Range(start, other.start);
    }

    public Range split() {
      int length = size() / 2;
      return new Range(start, start + length);
    }

    public boolean contains(int index) {
      return start <= index && index < end;
    }

    @Override
    public String toString() {
      return "["+start+";"+end+"]";
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Range)) {
        return false;
      }
      Range o = (Range) other;
      return start == o.start && end == o.end;
    }

    @Override
    public int hashCode() {
      return 31 * start + end;
    }

    private boolean verify() {
      return start <= end;
    }
  }

  private static class Run {
    final boolean good;
    final Range range;

    public Run(Result result, Range range) {
      assert result != Result.UNKNOWN;
      good = result == Result.GOOD;
      this.range = range;
    }

    public Run(String nonLastEntry) {
      int sep1 = nonLastEntry.indexOf(':');
      good = nonLastEntry.substring(0, sep1).trim().equals("good");
      String rangeEntry = nonLastEntry.substring(sep1 + 1).trim();
      range = new Range(rangeEntry);
    }

    public void write(Writer writer) throws IOException {
      writer.write(good ? "good" : "bad");
      writer.write(':');
      range.write(writer);
    }

    public boolean isBad() {
      return !good;
    }
  }

  private final String signature;
  private final DexApplication badApp;
  private final List<DexProgramClass> sortedGoodClasses;
  private final Map<DexType, Integer> indexMap;
  private final File stateFile;

  private List<Run> runs = new ArrayList<>();

  // Computed data
  private Range nextRange = null;

  public BisectState(DexApplication goodApp, DexApplication badApp, File stateFile) {
    this.badApp = badApp;
    this.stateFile = stateFile;
    signature = makeSignature(goodApp);
    if (!signature.equals(makeSignature(badApp))) {
      throw new CompilationError(
          "Bisecting application classes do not match classes in reference APK");
    }
    sortedGoodClasses = ImmutableList.copyOf(getSortedClasses(goodApp));
    ImmutableMap.Builder<DexType, Integer> builder = ImmutableMap.builder();
    for (int i = 0; i < sortedGoodClasses.size(); i++) {
      builder.put(sortedGoodClasses.get(i).type, i);
    }
    indexMap = builder.build();
  }

  public void read() throws IOException {
    if (stateFile == null) {
      return;
    }
    List<String> data = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(stateFile))) {
      if (!signature.equals(readSignature(reader))) {
        throw new CompilationError(
            "Bisection state file does not match the reference build signature");
      }
      String run;
      while ((run = reader.readLine()) != null) {
        data.add(run);
      }
    }
    if (data.isEmpty()) {
      return;
    }
    runs = new ArrayList<>(data.size());
    for (int i = 0; i < data.size() - 1; i++) {
      runs.add(new Run(data.get(i)));
    }
    nextRange = new Range(data.get(data.size() - 1));
  }

  public void setPreviousResult(Result result) {
    if (nextRange == null) {
      throw new CompilationError(
          "Invalid bisection state. Could not find information on previous runs.");
    }
    if (runs.size() == 0) {
      assert nextRange.equals(new Range(0, 0));
      if (result != Result.GOOD) {
        throw new CompilationError(
            "Expected good state for reference application run, got " + result);
      }
    }
    if (runs.size() == 1) {
      assert nextRange.equals(new Range(0, indexMap.size()));
      if (result != Result.BAD) {
        throw new CompilationError(
            "Expected bad state for input application run, got " + result);
      }
    }
    runs.add(new Run(result, nextRange));
    System.out.println("Marked range " + nextRange + ": " + result);
    nextRange = null;
  }

  public void verifySignature(DexApplication application) {
    if (signatureMismatch(makeSignature(application))) {
      throw new CompilationError(
          "Bisection state file does not match the application signature");
    }
  }

  public DexProgramClass getFinalClass() {
    if (nextRange.size() == 1) {
      int index = nextRange.start;
      return (DexProgramClass) badApp.definitionFor(sortedGoodClasses.get(index).type);
    }
    return null;
  }

  public DexApplication bisect() {
    assert nextRange == null;
    if (runs.isEmpty()) {
      // First run is a sanity check ensuring that the reference app results in a good state.
      nextRange = new Range(0, 0);
    } else if (runs.size() == 1) {
      // Second run is a sanity check ensuring that full application results in a bad state.
      nextRange = new Range(0, sortedGoodClasses.size());
    } else {
      // Subsequent runs continue with half of the currently-known bad range.
      Range badRange = getLastBadRange();
      if (badRange.isEmpty()) {
        throw new CompilationError("Bad range is empty. Cannot continue bisecting :-(");
      }
      if (badRange.size() == 1) {
        nextRange = badRange;
        return null;
      }
      System.out.println("Last bad range: " + badRange);
      nextRange = badRange.split();
    }
    System.out.println("Next bisection range: " + nextRange);
    int goodClasses = 0;
    int badClasses = 0;
    List<DexProgramClass> programClasses = new ArrayList<>();
    for (DexProgramClass clazz : badApp.classes()) {
      DexProgramClass goodClass = getGoodClass(clazz);
      if (goodClass != null) {
        programClasses.add(goodClass);
        ++goodClasses;
      } else {
        programClasses.add(clazz);
        assert !nextRange.isEmpty();
        ++badClasses;
      }
    }
    System.out.println("Class split is good: " + goodClasses + ", bad: " + badClasses);
    return badApp.builder().replaceProgramClasses(programClasses).build();
  }

  private DexProgramClass getGoodClass(DexProgramClass clazz) {
    Integer index = indexMap.get(clazz.type);
    if (index != null && !nextRange.contains(index)) {
      return sortedGoodClasses.get(index);
    }
    return null;
  }

  private Range getLastBadRange() {
    Range good = new Range(0, 0);
    for (int i = runs.size() - 1; i >= 0; i--) {
      Run run = runs.get(i);
      if (run.isBad()) {
        return run.range.sub(good);
      }
      good = good.add(run.range);
    }
    throw new Unreachable("Did not find any bad range in bisection state");
  }

  private boolean signatureMismatch(String appSignature) {
    return !signature.equals(appSignature);
  }

  private static String readSignature(BufferedReader reader) throws IOException {
    return reader.readLine();
  }

  public void write() throws IOException {
    if (stateFile == null) {
      return;
    }
    try (FileWriter writer = new FileWriter(stateFile, false)) {
      writer.write(signature);
      writer.write("\n");
      for (Run run : runs) {
        run.write(writer);
        writer.write("\n");
      }
      nextRange.write(writer);
      writer.write("\n");
      writer.flush();
    }
  }

  private static List<DexProgramClass> getSortedClasses(DexApplication app) {
    List<DexProgramClass> classes = new ArrayList<>(app.classes());
    app.dexItemFactory.sort(NamingLens.getIdentityLens());
    classes.sort(Comparator.comparing(DexProgramClass::getType));
    app.dexItemFactory.resetSortedIndices();
    return classes;
  }

  @SuppressWarnings("deprecation")
  private static String makeSignature(DexApplication app) {
    List<DexProgramClass> classes = getSortedClasses(app);
    StringBuilder builder = new StringBuilder();
    for (DexProgramClass clazz : classes) {
      builder.append(clazz.toString()).append(";");
    }
    return Hashing.sha256().hashString(builder.toString(), Charsets.UTF_8).toString();
  }
}
