<component name="libraryTable">
  <library name="%name%">
    <CLASSES>
%if(binaryJars)%
%binaryJars:{binaryJar |
      <root url="jar://$PROJECT_DIR$/%binaryJar%!/" />
}%%endif%
%if(classPaths)%
%classPaths:{classPath |
      <root url="file://$PROJECT_DIR$/%classPath%" />
}%%endif%
    </CLASSES>
%if(javadocUrls)%
    <JAVADOC>
%javadocUrls:{javadocUrl |
      <root url="%javadocUrl%" />
}%    </JAVADOC>
%else%
    <JAVADOC />
%endif%
%if(sourceJars)%
    <SOURCES>
%sourceJars:{sourceJar |
      <root url="jar://$PROJECT_DIR$/%sourceJar%!/" />
}%    </SOURCES>
%else%
    <SOURCES />
%endif%
  </library>
</component>