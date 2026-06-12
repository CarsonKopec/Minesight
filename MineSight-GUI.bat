@echo off
rem Launches the MineSight Control Panel without a console window.
cd /d "%~dp0engine"
start "" ".venv\Scripts\pythonw.exe" -m minesight_gui
