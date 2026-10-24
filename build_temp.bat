@echo off
set JAVA_HOME=C:\jdk17
set PATH=%PATH%;C:\jdk17\bin
cd launcher
gradlew.bat clean assembleDebug
