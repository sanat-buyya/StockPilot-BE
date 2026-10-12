@echo off
rem Starts a real embedded PostgreSQL on localhost:54329 (data kept in .pgdata). No install or Docker needed.
setlocal
cd /d "%~dp0.."
if not exist cp.txt call mvnw.cmd -B -q test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
for /f "usebackq delims=" %%i in ("cp.txt") do set CP=%%i
java -cp "target/test-classes;target/classes;%CP%" com.stockpilot.LocalDevPostgres
