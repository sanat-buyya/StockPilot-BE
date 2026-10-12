@echo off
rem Runs the API against the local embedded dev database (see dev-db.cmd) with demo data.
rem The JWT secret is generated per run for local use only; nothing here is a real secret.
setlocal
if "%JWT_SECRET%"=="" set JWT_SECRET=local-dev-only-%RANDOM%%RANDOM%%RANDOM%%RANDOM%-0123456789abcdef
if "%DB_URL%"=="" set DB_URL=jdbc:postgresql://localhost:54329/postgres
if "%DB_USER%"=="" set DB_USER=postgres
if "%SEED_DEMO_DATA%"=="" set SEED_DEMO_DATA=true
if "%SEED_DEMO_PASSWORD%"=="" set SEED_DEMO_PASSWORD=Demo@12345
if "%PORT%"=="" set PORT=8090
if "%CORS_ORIGINS%"=="" set CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
pushd "%~dp0.."
call "%CD%\mvnw.cmd" -B -q spring-boot:run
