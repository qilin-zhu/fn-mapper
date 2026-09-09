@echo off
rem Gradle wrapper Æô¶¯½Å±¾ (Windows)
setlocal
set "DIRNAME=%~dp0"

if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_CMD=java.exe"
)

"%JAVA_CMD%" -cp "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
