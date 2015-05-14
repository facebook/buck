<component name="libraryTable">
  <library name="%name%">
    <CLASSES>
      <root url="jar://$PROJECT_DIR$/%binaryJar%!/" />
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
