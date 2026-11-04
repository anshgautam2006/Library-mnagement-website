@echo off
setlocal
cd /d "%~dp0"
title BBAU Central Library - Java Server

if not exist "server\lib\mysql-connector-j-9.5.0.jar" (
  echo Missing MySQL Connector/J: server\lib\mysql-connector-j-9.5.0.jar
  echo Restore the server\lib folder before starting the site.
  pause
  exit /b 1
)

if not exist "server\classes" mkdir "server\classes"
javac -encoding UTF-8 -cp "server\lib\*" -d "server\classes" "server\LibraryServer.java"
if errorlevel 1 (
  echo Java compilation failed. Review the errors above.
  pause
  exit /b 1
)

echo.
echo Checking the local MySQL server...
powershell -NoProfile -ExecutionPolicy Bypass -File "server\ensure-mysql.ps1"
if errorlevel 1 (
  echo MySQL is not ready. The library server was not started.
  pause
  exit /b 1
)

if not defined LMS_DB_PASSWORD goto secure_run

:run_java
java -Dlibrary.root="%CD%" -Dlibrary.openBrowser=true -cp "server\classes;server\lib\*" LibraryServer
if errorlevel 1 (
  echo The library server stopped with an error.
  pause
)
goto :eof

:secure_run
powershell -NoProfile -ExecutionPolicy Bypass -File "server\run-with-mysql-password.ps1"
if errorlevel 1 (
  echo The library server stopped with an error.
  pause
)
