package com.example.activity;

import android.test.ActivityInstrumentationTestCase2;

public class MyFirstActivityTest extends ActivityInstrumentationTestCase2<MyFirstActivity> {

  public MyFirstActivityTest() {
    super(MyFirstActivity.class);
  }

  public void testGetActivity() {
    assertNotNull("test getActivity()", getActivity());
  }

}
