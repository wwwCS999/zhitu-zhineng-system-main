@echo off
chcp 65001 >nul
setlocal
set "ROOT=%~dp0\..\.."

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

cd /d "%ROOT%\backend"
echo [Zhitu] Starting Spring Boot backend...
echo [Zhitu] AI_BASE_URL=%AI_BASE_URL%
echo [Zhitu] AI_MODEL=%AI_MODEL%
echo [Zhitu] AI_RESUME_MODEL=%AI_RESUME_MODEL%

set "JAVA_NET_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true -Djava.net.useSystemProxies=true"
set "JAR=%ROOT%\backend\target\zhitu-backend-1.0.0.jar"
set "JAVA_BIN=D:\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe"
if exist "%JAR%" (
  echo [Zhitu] Running packaged backend jar...
  cd /d "%ROOT%"
  if exist "%JAVA_BIN%" (
    "%JAVA_BIN%" %JAVA_NET_OPTS% -jar "%JAR%"
  ) else (
    java %JAVA_NET_OPTS% -jar "%JAR%"
  )
  goto END
)

echo [Zhitu] Packaged jar not found, falling back to Maven spring-boot:run...
set "MAVEN_BIN=D:\tools\apache-maven-3.9.9\bin\mvn.cmd"
if exist "%MAVEN_BIN%" (
  "%MAVEN_BIN%" spring-boot:run -Dspring-boot.run.jvmArguments="%JAVA_NET_OPTS%"
) else (
  mvn spring-boot:run -Dspring-boot.run.jvmArguments="%JAVA_NET_OPTS%"
)

:END
if /I not "%~1"=="--no-pause" pause
