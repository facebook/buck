Introduction to the JDOM project
================================

Please see the JDOM web site at http://jdom.org/
and GitHub repository at https://github.com/hunterhacker/jdom/

Quick-Start for JDOM
=====================
See the github wiki for a Primer on using JDOM:
https://github.com/hunterhacker/jdom/wiki/JDOM2-A-Primer

Also see the web site http://jdom.org/downloads/docs.html.  It has links to
numerous articles and books covering JDOM.


Installing the build tools
==========================

The JDOM build system is based on Apache Ant.  Ant is a little but very
handy tool that uses a build file written in XML (build.xml) as building
instructions. For more information refer to "http://ant.apache.org".

The only thing that you have to make sure of is that the "JAVA_HOME"
environment property is set to match the top level directory containing the
JVM you want to use. For example:

C:\> set JAVA_HOME=C:\jdk1.6

or on Mac:

% setenv JAVA_HOME /System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home
  (csh)
> JAVA_HOME=/System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home; export JAVA_HOME
  (ksh, bash)

or on Unix:

% setenv JAVA_HOME /usr/local/java
  (csh)
> JAVA_HOME=/usr/java; export JAVA_HOME
  (ksh, bash)

That's it!


Building instructions
=====================

If you do not have the full source code it can be cloned from GitHub. The JDOM
project at https://github.com/hunterhacker/jdom has the instructions and source
URL to make the git clone easy.

You will need to have Apache Ant 1.8.2 or later, and you will need Java JDK 1.6
or later.

Ok, let's build the code. First, make sure your current working directory is
where the build.xml file is located. Then run "ant".

If everything is right and all the required packages are visible, this action
will generate a file called "jdom-2.x-20yy.mm.dd.HH.MM.zip" in the
"./build/package" directory. This is the same 'zip' file that is distributed
as the official JDOM distribution.

The name of the zip file (and the jar names inside the zip) is controlled by
the two ant properties 'name' and 'version'. The package is called
"${name}-${version}.zip". The 'official' JDOM Build process is done by
creating a file 'build.properties' in the 'top' folder of the JDOM code, and
it contains the single line (or whatever the appropriate version is):

version=2.0.0

If your favourite Java IDE happens to be Eclipse, you can run the 'eclipse' ant
target, and that will configure your Eclipse project to have all the right
'source' folders, and 'Referenced Libraries'. After running the 'ant eclipse'
target, you should refresh your Eclipse project, and you should have a project
with no errors or warnings.


Build targets
=============

The build system is not only responsible for compiling JDOM into a jar file,
but is also responsible for creating the HTML documentation in the form of
javadocs.

These are the meaningful targets for this build file:

 - package [default] -> generates ./build/package/jdom*.zip
 - compile -> compiles the source code
 - javadoc -> generates the API documentation in ./build/javadocs
 - junit -> runs the JUnit tests
 - coverage -> generates test coverage metrics
 - eclipse -> generates an Eclipse project (source folders, jars, etc)
 - clean -> restores the distribution to its original and clean state
 - maven -> generates the package, and makes a 'bundle' for maven-central

To learn the details of what each target does, read the build.xml file.  It is
quite understandable.


Bug Reports
===========

Bug reports go to the jdom-interest list at jdom.org.  But *BEFORE YOU POST*
make sure you've tested against the LATEST code available from GitHub (or the
daily snapshot).  Odds are good your bug has already been fixed.  If it hasn't
been fixed in the latest version, then when posting *BE SURE TO SAY* which
code version you tested against.  For example, "GitHub from October 3rd".  Also
be sure to include enough information to reproduce the bug and full exception
stack traces.  You might also want to read the FAQ at http://jdom.org to find
out if your problem is not really a bug and just a common misunderstanding
about how XML or JDOM works.


Searching for Information
=========================

The JDOM mailing lists are archived and easily searched at
http://jdom.markmail.org.
