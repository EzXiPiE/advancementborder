@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
title AdvancementBorder - Seed Scanner

echo [1/4] Автопоиск установленной Java 21+...
set "FOUND_JAVA="

:: 1. Проверяем стандартную папку JDK от IntelliJ / OpenIDE (%USERPROFILE%\.jdks)
if exist "%USERPROFILE%\.jdks" (
    for /d %%D in ("%USERPROFILE%\.jdks\*21*" "%USERPROFILE%\.jdks\*22*" "%USERPROFILE%\.jdks\*23*" "%USERPROFILE%\.jdks\*24*" "%USERPROFILE%\.jdks\*25*") do (
        if exist "%%~D\bin\java.exe" set "FOUND_JAVA=%%~D"
    )
)

:: 2. Проверяем стандартные папки в Program Files
if not defined FOUND_JAVA (
    for %%B in ("C:\Program Files\Java" "C:\Program Files\Eclipse Adoptium" "C:\Program Files\Microsoft" "C:\Program Files\BellSoft" "C:\Program Files\Amazon Corretto" "C:\Program Files\Zulu") do (
        if exist "%%~B" (
            for /d %%D in ("%%~B\*21*" "%%~B\*22*" "%%~B\*23*" "%%~B\*24*" "%%~B\*25*") do (
                if exist "%%~D\bin\java.exe" set "FOUND_JAVA=%%~D"
            )
        )
    )
)

:: 3. Если нашли новую Java — подставляем её для текущего окна
if defined FOUND_JAVA (
    echo Найдена подходящая Java: !FOUND_JAVA!
    set "JAVA_HOME=!FOUND_JAVA!"
    set "PATH=!JAVA_HOME!\bin;!PATH!"
) else (
    echo Внимание: Java 21+ не найдена в стандартных папках, используем системную...
)

echo [2/4] Подготовка папки run и принятие EULA...
if not exist "run" mkdir "run"
echo eula=true> "run\eula.txt"

echo [3/4] Запуск выделенного сервера со сканером сидов...
echo Для остановки и сохранения введите: stop
echo.

call gradlew.bat runServer -Dadvancementborder.autoscan=true --no-configuration-cache

echo.
echo [4/4] Работа завершена. Найденные сиды лежат в папке run\found_seeds.json
pause
