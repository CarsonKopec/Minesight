@echo off
rem MineSight Farm Agent - run this on REMOTE machines joining a farm.
cd /d "%~dp0engine"
if exist .venv\Scripts\pythonw.exe (
    start "" .venv\Scripts\pythonw.exe -m minesight_gui.farm_agent
) else (
    start "" pythonw -m minesight_gui.farm_agent
)
