@echo off
set JAVA_HOME=C:\Users\livet\AppData\Local\Temp\openjdk17\jdk-17.0.18+8
set KEYTOOL=%JAVA_HOME%\bin\keytool.exe
set KEYSTORE=%~dp0taptotype-release-key.jks

if exist "%KEYSTORE%" del "%KEYSTORE%"

"%KEYTOOL%" -genkeypair -v -keystore "%KEYSTORE%" -keyalg RSA -keysize 2048 -validity 10000 -alias taptotype -dname "CN=TapToType, OU=Development, O=TapToType, L=Unknown, ST=Unknown, C=US" -storepass changeme123 -keypass changeme123

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: Keystore created!
    echo Location: %KEYSTORE%
) else (
    echo.
    echo FAILED: keytool returned error code %ERRORLEVEL%
)
pause
