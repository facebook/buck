package com.example.javacextras;

import java.util.ArrayList;
import java.util.List;

class UnsafeGenerics {
  public static void main(String[] args) {
    final List noGenerics = new ArrayList();
    noGenerics.add("foo");

    System.out.println(noGenerics.size());
  }
}
