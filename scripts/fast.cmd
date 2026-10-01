@echo off
rem Delegates to fast.sh through Git Bash. Usage: scripts\fast.cmd test VersionTest
bash "%~dp0fast.sh" %*
