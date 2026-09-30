@echo off
rem CATCHY: build and start telemetry-service, both demo services and the dashboard (Windows).
rem Usage: scripts\start-all.bat      (stop with scripts\stop-all.bat)
setlocal enabledelayedexpansion
cd /d "%~dp0.."

if not exist .env (
  copy /y .env.example .env >nul
  echo Created .env from .env.example ^(dev placeholders^).
)
rem Load .env without overriding variables already set by the caller (e.g. set CATCHY_DASHBOARD_PORT=5180)
for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do if not "%%a"=="" if not defined %%a set "%%a=%%b"

if not defined CATCHY_TELEMETRY_PORT set CATCHY_TELEMETRY_PORT=8090
if not defined CATCHY_CLAIMS_PORT set CATCHY_CLAIMS_PORT=8091
if not defined CATCHY_ELIGIBILITY_PORT set CATCHY_ELIGIBILITY_PORT=8092
if not defined CATCHY_DASHBOARD_PORT set CATCHY_DASHBOARD_PORT=5173

if not exist logs mkdir logs
echo ==^> Building ^(tests skipped; run mvnw test separately^)
call mvnw.cmd -q -B -DskipTests package
if errorlevel 1 (echo Build failed & exit /b 1)

echo ==^> Starting telemetry-service on :%CATCHY_TELEMETRY_PORT%
set SERVER_PORT=%CATCHY_TELEMETRY_PORT%
start "CATCHY telemetry" /min cmd /c "java -jar telemetry-service\target\telemetry-service-1.0.0-SNAPSHOT.jar > logs\telemetry.log 2>&1"
call :wait http://localhost:%CATCHY_TELEMETRY_PORT%/api/v1/health telemetry-service

echo ==^> Starting demo-claims-service on :%CATCHY_CLAIMS_PORT%
set SERVER_PORT=%CATCHY_CLAIMS_PORT%
start "CATCHY claims" /min cmd /c "java -jar demo-claims-service\target\demo-claims-service-1.0.0-SNAPSHOT.jar > logs\claims.log 2>&1"
echo ==^> Starting demo-eligibility-service on :%CATCHY_ELIGIBILITY_PORT%
set SERVER_PORT=%CATCHY_ELIGIBILITY_PORT%
start "CATCHY eligibility" /min cmd /c "java -jar demo-eligibility-service\target\demo-eligibility-service-1.0.0-SNAPSHOT.jar > logs\eligibility.log 2>&1"
call :wait http://localhost:%CATCHY_CLAIMS_PORT%/api/health demo-claims-service
call :wait http://localhost:%CATCHY_ELIGIBILITY_PORT%/api/health demo-eligibility-service

echo ==^> Starting dashboard on :%CATCHY_DASHBOARD_PORT%
pushd dashboard
if not exist node_modules call npm install --no-audit --no-fund
set CATCHY_API_URL=http://localhost:%CATCHY_TELEMETRY_PORT%
start "CATCHY dashboard" /min cmd /c "npm run dev -- --port %CATCHY_DASHBOARD_PORT% --strictPort > ..\logs\dashboard.log 2>&1"
popd

echo.
echo CATCHY is starting.
echo   Dashboard         http://localhost:%CATCHY_DASHBOARD_PORT%   ^(pick a role on the login page^)
echo   Telemetry API     http://localhost:%CATCHY_TELEMETRY_PORT%/api/v1/health
echo   Claims demo       http://localhost:%CATCHY_CLAIMS_PORT%/api/health
echo   Eligibility demo  http://localhost:%CATCHY_ELIGIBILITY_PORT%/api/health
echo Generate traffic: scripts\demo-load.sh ^(Git Bash^) or the curl commands in README.md.  Logs: .\logs  Stop: scripts\stop-all.bat
exit /b 0

:wait
for /l %%i in (1,1,60) do (
  curl -fs %1 >nul 2>&1 && (echo     %2 is up & exit /b 0)
  timeout /t 1 /nobreak >nul
)
echo     %2 did not become healthy; see logs\
exit /b 1
