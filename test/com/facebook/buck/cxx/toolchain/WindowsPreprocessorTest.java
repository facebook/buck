package com.facebook.buck.cxx.toolchain;

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.Tool;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.easymock.EasyMock;
import org.hamcrest.junit.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WindowsPreprocessorTest {
  @Rule public final ExpectedException expectedException = ExpectedException.none();

  private WindowsPreprocessor windowsPreprocessor;

  @Before
  public void setUp() {
    Tool tool = EasyMock.createMock(Tool.class);

    this.windowsPreprocessor = new WindowsPreprocessor(tool);
  }

  @Test
  public void testPrefixHeaderArgsHandlesSimpleHeaderFilePaths() {
    String headerPathStr = "stdafx.h";
    Path headerPath = Paths.get(headerPathStr);

    assertThat(windowsPreprocessor.prefixHeaderArgs(headerPath), contains("/Yc", headerPathStr));
  }

  @Test
  public void testPrefixHeaderArgsHandlesNestedHeaderFilePaths() {
    String headerPathStr = "include/subdir/stdafx.h";
    Path headerPath = Paths.get(headerPathStr);

    assertThat(windowsPreprocessor.prefixHeaderArgs(headerPath), contains("/Yc", headerPathStr));
  }

  @Test
  public void testPrecompiledHeaderArgsHandlesSimpleHeaderFilePaths() {
    String headerPathStr = "stdafx.h";
    String pchPathStr = "stdafx.pch";
    Path headerPath = Paths.get(headerPathStr);

    assertThat(windowsPreprocessor.precompiledHeaderArgs(headerPath), contains("/Yu", pchPathStr));
  }

  @Test
  public void testPrecompiledHeaderArgsHandlesNestedHeaderFilePaths() {
    String headerPathStr = "include/subdir/stdafx.h";
    String pchPathStr = "stdafx.pch";
    Path headerPath = Paths.get(headerPathStr);

    assertThat(windowsPreprocessor.precompiledHeaderArgs(headerPath), contains("/Yu", pchPathStr));
  }
}
