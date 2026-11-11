@echo off
rem ---------------------------------------------------------------------------------------------
rem  Starts the OneGallery gateway alongside ComfyUI, in the same window, and returns at once.
rem
rem  Call it from the script that starts ComfyUI, BEFORE the line that starts ComfyUI itself:
rem
rem      set "COMFYUI_DIR=C:\ComfyUI_windows_portable\ComfyUI"
rem      call "C:\OneGallery\comfyui-bridge\gateway\start-with-comfyui.bat"
rem
rem  COMFYUI_DIR   required - the ComfyUI folder, the one holding models\, input\ and output\
rem  COMFYUI_URL   optional - where ComfyUI answers (default http://127.0.0.1:8188); set it if
rem                ComfyUI is started with another --port
rem  ONEGALLERY_JOBS    optional - the jobs folder, if not the "workflows" folder beside gateway
rem  ONEGALLERY_PYTHON  optional - a Python 3.10+ that already has aiohttp, cryptography and
rem                Pillow. Without it the gateway uses its own .venv, made on first run with the
rem                "python" (or "py") on PATH.
rem
rem  The gateway prints its pairing code in this window when it starts. Closing the window
rem  stops both. Running this twice is harmless: the second gateway sees the port taken and
rem  leaves.
rem ---------------------------------------------------------------------------------------------
setlocal
set "GATEWAY=%~dp0"
if "%GATEWAY:~-1%"=="\" set "GATEWAY=%GATEWAY:~0,-1%"

rem (No ( ) blocks around paths below: a folder like "Program Files (x86)" would end them early)
if not defined COMFYUI_DIR goto :no_dir
if not exist "%COMFYUI_DIR%\models\" echo [OneGallery] There is no models folder in COMFYUI_DIR - point it at the ComfyUI folder itself.
if not defined COMFYUI_URL set "COMFYUI_URL=http://127.0.0.1:8188"

set "PY=%ONEGALLERY_PYTHON%"
if defined PY goto :start

set "PY=%GATEWAY%\.venv\Scripts\python.exe"
if exist "%PY%" goto :start
echo [OneGallery] First run: setting up the gateway's Python packages...
set "SYSPY=python"
where python >nul 2>nul || set "SYSPY=py -3"
%SYSPY% -m venv "%GATEWAY%\.venv" || goto :fail
"%PY%" -m pip install -q -r "%GATEWAY%\requirements.txt" || goto :fail

:start
rem /b: same window, no new one; /d: run from the gateway folder so the package is found
set "JOBS="
if defined ONEGALLERY_JOBS set JOBS=--workflows "%ONEGALLERY_JOBS%"
start "OneGallery gateway" /b /d "%GATEWAY%" "%PY%" -u -m onegallery_gateway --comfyui-dir "%COMFYUI_DIR%" --comfy-url "%COMFYUI_URL%" %JOBS%
goto :done

:no_dir
echo [OneGallery] COMFYUI_DIR is not set, so the gateway was not started. See start-with-comfyui.bat.
goto :done

:fail
echo [OneGallery] Could not set up the gateway. Install Python 3.10 or newer from python.org with
echo [OneGallery] "Add to PATH" ticked, or set ONEGALLERY_PYTHON to a Python with aiohttp, cryptography, Pillow.

:done
endlocal
exit /b 0
