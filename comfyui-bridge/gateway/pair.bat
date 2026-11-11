@echo off
rem A new four-digit pairing code, for when the one shown at start-up has run out.
rem The gateway has to be running (it is, whenever ComfyUI was started with it).
setlocal
set "PY=%ONEGALLERY_PYTHON%"
if not defined PY set "PY=%~dp0.venv\Scripts\python.exe"
cd /d "%~dp0"
"%PY%" -m onegallery_gateway pair
endlocal
pause
