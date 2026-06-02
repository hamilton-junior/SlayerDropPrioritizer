@echo off
call .\gradlew.bat run 2>&1 | findstr "SlayerDropPrioritizerPlugin"