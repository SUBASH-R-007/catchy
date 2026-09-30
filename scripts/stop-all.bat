@echo off
rem Stops everything started by start-all.bat by freeing the CATCHY ports.
for %%p in (5173 8090 8091 8092) do (
  for /f "tokens=5" %%i in ('netstat -ano ^| findstr /r /c:":%%p .*LISTENING"') do (
    taskkill /f /pid %%i >nul 2>&1 && echo freed port %%p ^(pid %%i^)
  )
)
exit /b 0
