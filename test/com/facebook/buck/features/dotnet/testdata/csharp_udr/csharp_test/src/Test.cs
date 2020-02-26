using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;

using ConsoleApp1;

namespace UnitTestProject1
{
    [TestClass]
    public class Test
    {
        [TestMethod]
        public void lib1Formats()
        {
            Assert.AreEqual("Lib1.format: format_string: bar", new Lib1("bar").format("format_string: {0}"));
        }

        [TestMethod]
        public void lib2Formats()
        {
            Assert.AreEqual("Lib2.format: format_string: bar", new Lib2("bar").format("format_string: {0}"));
        }
    }
}
