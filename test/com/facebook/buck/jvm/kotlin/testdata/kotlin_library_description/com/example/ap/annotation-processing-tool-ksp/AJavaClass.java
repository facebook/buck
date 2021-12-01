package com.example.ap;

class AJavaClass {
  public void foo() {
    new JavaClassWithJavaAnnotation_kspgen();
    new KotlinClassWithJavaAnnotation_kspgen();
    new JavaClassWithKotlinAnnotation_kspgen();
    new KotlinClassWithKotlinAnnotation_kspgen();
  }
}
