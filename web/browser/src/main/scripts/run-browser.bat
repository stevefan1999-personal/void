@echo off
setlocal
cd /d "%~dp0"
if not defined VOID_JAVA_HEAP_MIB set "VOID_JAVA_HEAP_MIB=2048"
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java" "-Xmx%VOID_JAVA_HEAP_MIB%m" -jar "@serverJar@" %*
) else (
  java "-Xmx%VOID_JAVA_HEAP_MIB%m" -jar "@serverJar@" %*
)
exit /b %errorlevel%
