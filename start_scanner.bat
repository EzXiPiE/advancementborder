@echo off
chcp 65001 > nul
title AdvancementBorder - Seed Scanner

echo [1/3] Подготовка папки run и принятие EULA...
if not exist "run" mkdir "run"
echo eula=true> "run\eula.txt"

echo [2/3] Запуск выделенного сервера со сканером сидов...
echo Для остановки и сохранения введите: stop
echo.

call gradlew.bat runServer -Dadvancementborder.autoscan=true --args="--nogui"

echo.
echo [3/3] Работа завершена. Найденные сиды лежат в папке run\found_seeds.json
pause
