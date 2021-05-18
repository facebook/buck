package com.example.deps.c;

import com.example.deps.iface.IFace;

public class B implements IFace {

  @Override
  public void print(String msg) {
    System.out.println(msg);
  }
}
