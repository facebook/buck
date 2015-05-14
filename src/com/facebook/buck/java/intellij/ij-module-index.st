<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
%modules:{module |
      <module fileurl="%module.fileUrl%" filepath="%module.filePath%" %\\%
        %if (module.group)% group="%module.group%" %endif% />
}%
   </modules>
  </component>
</project>