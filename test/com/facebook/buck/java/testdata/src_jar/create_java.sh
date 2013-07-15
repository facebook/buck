#!/bin/bash

cd $TMP

# Write Yang.java.
echo "package com.example;

public class Yang {

  private Yin yin;

  public void setYin(Yin yin) {
    this.yin = yin;
  }

  public Yin getYin() {
    return yin;
  }
}
" > Yang.java

# Write Main.java.
echo "package com.example;

public class Main {

  public static void main(String... args) {
    Yin yin = new Yin();
    Yang yang = new Yang();
    yin.setYang(yang);
    yang.setYin(yin);
  }
}
" > Main.java

jar cf "$1" Yang.java Main.java
