package com.example.gwt.client;

import com.google.gwt.core.client.EntryPoint;
import java.io.DataInputStream;

public class A implements EntryPoint {

  public void onModuleLoad() {
    // DataInputStream is not provided by GWT default Java runtime emulation
    new DataInputStream();
  }
}
