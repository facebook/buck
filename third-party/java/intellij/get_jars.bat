if exist %1 (
    xcopy %1\annotations.jar . /F
    xcopy %1\extensions.jar . /F
    xcopy %1\idea.jar . /F
    xcopy %1\openapi.jar . /F
    xcopy %1\util.jar . /F
) else (
    echo "Please provide the path to the sdk"
    echo "Example usage: 'get_jars.bat ^"Z:\Applications\IntelliJ IDEA 15 CE.app\Contents\lib^"'"
)
