using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UnitTestProject1
{
    [TestClass]
    public class SimpleTest
    {
        [TestMethod]
        public void passes()
        {
            Assert.AreEqual("foo", "foo");
        }
    }
}
