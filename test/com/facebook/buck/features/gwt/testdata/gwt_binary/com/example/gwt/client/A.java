package com.example.gwt.client;

import com.example.gwt.shared.MyFactory;
import com.example.gwt.shared.MyFieldAnnotation;
import com.google.gwt.core.client.EntryPoint;
import java.io.DataOutputStream;

@MyFieldAnnotation
public class A implements EntryPoint {

  public void onModuleLoad() {
    // DataOutputStream is not provided by GWT default Java runtime emulation
    new DataOutputStream(null);

    // Use the annotation processing generated factory
    new MyFactory();
  }
}
