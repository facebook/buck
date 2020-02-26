using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ConsoleApp1
{
    public class Lib2
    {
        private string formatString;

        public Lib2(String formatString)
        {
            this.formatString = formatString;
        }

        public String format(String replacement)
        {
            return String.Format("Lib2.format: {0}", String.Format(this.formatString, replacement));
        }
    }
}
