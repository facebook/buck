package com.example.javacextras;

import java.util.List;
import java.util.ArrayList;

class UnsafeGenerics {
  public static void main(String[] args) {
    final List noGenerics = new ArrayList();
    noGenerics.add("foo");

    System.out.println(noGenerics.size());
  }
}
