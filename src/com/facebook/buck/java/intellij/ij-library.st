<component name="libraryTable">
  <library name="%name%">
    <CLASSES>
%if(binaryJar)%
      <root url="jar://$PROJECT_DIR$/%binaryJar%!/" />
%endif%
%if(classPaths)%
  %classPaths:{classPath |
      <root url="file://$PROJECT_DIR$/%classPath%" />
  }%
%endif%
    </CLASSES>
%if(javadocUrl)%
    <JAVADOC>
      <root url="%javadocUrl%" />
    </JAVADOC>
%endif%
%if(sourceJar)%
    <SOURCES>
      <root url="jar://$PROJECT_DIR$/%sourceJar%!/" />
    </SOURCES>
%endif%
  </library>
</component>
