package com.example.backticks

fun main(args: Array<String>) {
  `this method name has backticks and spaces`()
}

private fun `this method name has backticks and spaces`() {
  `this method calls a java function named a kotlin keyword`()
}

private fun `this method calls a java function named a kotlin keyword`() {
  JavaClass().`internal`()
}
