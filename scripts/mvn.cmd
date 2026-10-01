@echo off
rem Runs Maven with the JDK 21 this project requires. Usage: scripts\mvn.cmd verify
setlocal
if "%JRSUPGRADE_JDK%"=="" set "JRSUPGRADE_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
if not exist "%JRSUPGRADE_JDK%\bin\java.exe" ( echo JDK 21 not found at "%JRSUPGRADE_JDK%". Set JRSUPGRADE_JDK. 1>&2 & exit /b 1 )
set "JAVA_HOME=%JRSUPGRADE_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0..\mvnw.cmd" -B %*
