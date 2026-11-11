@echo off
rem Starts the OneGallery gateway. First run: creates .venv and installs the three packages it needs.
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv || goto :fail
  .venv\Scripts\python -m pip install -q -r requirements.txt || goto :fail
)
.venv\Scripts\python -m onegallery_gateway %*
goto :eof
:fail
echo Could not set up the gateway. Is Python 3.10 or newer installed and on PATH?
exit /b 1
