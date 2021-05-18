package com.example.deps.a;

import com.example.deps.iface.IFace;

public class Z implements IFace {

  @Override
  public void print(String msg) {
    System.out.println(msg);
  }
}
