package com.example.ap

class AKotlinClass {
    fun foo() {
      KotlinClassForMultiroundFromKaptToKsp_kaptgen()

      // For now, KSP cannot process generated code from KAPT.
      // Uncommenting this line would break the test
      //      KotlinClassForMultiroundFromKaptToKsp_kaptgen_kspgen()
    }
}
